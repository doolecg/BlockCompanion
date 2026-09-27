package io.blockcompanion.server;

import io.blockcompanion.core.autobuild.BuildWorld;
import io.blockcompanion.core.chests.LinkedChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoBuild's world on a Fabric or NeoForge server: reads blocks and sets them straight into the level (neighbour
 * updates on, nothing used or clicked, so doors and trapdoors keep the state the schematic gives them and no container
 * opens). Server thread only; no client classes.
 */
final class ModBuildWorld implements BuildWorld {
    /**
     * How AutoBuild sets blocks, like Create's schematic cannon: clients are told, but neighbours get no block or shape
     * updates. The schematic's states already carry their connections, and nothing next to the build reacts (water
     * doesn't flow into a gap, observers and redstone stay quiet), which also keeps the server's work per block low.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final MinecraftServer server;
    /** For putting broken blocks' drops into the linked chests. */
    private final ModChestAccess chests;
    /** Core state to game state; a missing block maps to the empty Optional. */
    private final Map<io.blockcompanion.core.model.BlockState, Optional<BlockState>> toGame = new ConcurrentHashMap<>();

    ModBuildWorld(MinecraftServer server, ModChestAccess chests) {
        this.server = server;
        this.chests = chests;
    }

    private ServerLevel level(String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private BlockState game(io.blockcompanion.core.model.BlockState s) {
        return toGame.computeIfAbsent(s, ModBuildWorld::resolve).orElse(null);
    }

    private static Optional<BlockState> resolve(io.blockcompanion.core.model.BlockState s) {
        Identifier id = Identifier.tryParse(s.name());
        if (id == null) return Optional.empty();
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) return Optional.empty();
        BlockState mc = block.get().defaultBlockState();
        for (var e : s.properties().entrySet()) {
            Property<?> prop = block.get().getStateDefinition().getProperty(e.getKey());
            if (prop != null) mc = with(mc, prop, e.getValue());
        }
        return Optional.of(mc);
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
    }

    private static io.blockcompanion.core.model.BlockState core(BlockState mc) {
        String name = BuiltInRegistries.BLOCK.getKey(mc.getBlock()).toString();
        if (mc.getProperties().isEmpty()) return io.blockcompanion.core.model.BlockState.of(name);
        Map<String, String> props = new TreeMap<>();
        mc.getValues().forEach(v -> props.put(v.property().getName(), v.valueName()));
        return io.blockcompanion.core.model.BlockState.of(name, props);
    }

    @Override
    public boolean dimensionExists(String dimension) {
        return server.isRunning() && level(dimension) != null;
    }

    @Override
    public boolean isLoaded(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level != null && level.isLoaded(new BlockPos(x, y, z));
    }

    @Override
    public io.blockcompanion.core.model.BlockState get(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return io.blockcompanion.core.model.BlockState.AIR;
        return core(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public Check check(String dimension, int x, int y, int z, io.blockcompanion.core.model.BlockState state) {
        ServerLevel level = level(dimension);
        BlockState mc = game(state);
        if (level == null || mc == null) return Check.UNKNOWN_BLOCK;
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isInWorldBounds(pos)) return Check.UNKNOWN_BLOCK;
        // Sand and gravel over a gap would fall; torches, plants, rails and the like need what they stand on.
        if (mc.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) return Check.UNSUPPORTED;
        if (!mc.canSurvive(level, pos)) return Check.UNSUPPORTED;
        return Check.OK;
    }

    @Override
    public boolean place(String dimension, int x, int y, int z, io.blockcompanion.core.model.BlockState state) {
        ServerLevel level = level(dimension);
        BlockState mc = game(state);
        if (level == null || mc == null) return false;
        return level.setBlock(new BlockPos(x, y, z), mc, PLACE_FLAGS);
    }

    @Override
    public Removal removal(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return Removal.NEVER;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState mc = level.getBlockState(pos);
        // Bedrock, barriers, portals, command and structure blocks can't be broken by hand: never by AutoBuild either.
        if (mc.isAir() || mc.getDestroySpeed(level, pos) < 0) return Removal.NEVER;
        if (mc.hasBlockEntity()) return Removal.OTHER;
        return mc.isCollisionShapeFullBlock(level, pos) ? Removal.SOLID : Removal.OTHER;
    }

    @Override
    public boolean replace(String dimension, int x, int y, int z, io.blockcompanion.core.model.BlockState state, Drops drops) {
        ServerLevel level = level(dimension);
        BlockState mc = state.isAir() ? Blocks.AIR.defaultBlockState() : game(state);
        if (level == null || mc == null) return false;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState old = level.getBlockState(pos);
        BlockEntity be = old.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        List<ItemStack> stacks = new ArrayList<>();
        // What a container held, always (as when a player breaks it), taken out first: it isn't spilled twice, and a
        // shulker box drops empty instead of with a copy of it...
        if (be instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) stacks.add(c.getItem(i).copy());
            c.clearContent();
        }
        // ...and what breaking it by hand would drop (nothing in creative).
        if (drops != null) stacks.addAll(Block.getDrops(old, level, pos, be));
        LinkedHashSet<LinkedChests.Pos> filled = new LinkedHashSet<>();
        for (ItemStack st : stacks) {
            ItemStack left = st;
            // Into the linked chests in order (survival), the rest on the ground.
            if (drops != null) {
                for (LinkedChests.Pos c : drops.chests()) {
                    if (left.isEmpty()) break;
                    int before = left.getCount();
                    left = chests.insert(c.dimension(), c.x(), c.y(), c.z(), left);
                    if (left.getCount() != before) filled.add(c);
                }
            }
            if (!left.isEmpty()) Block.popResource(level, pos, left);
        }
        if (drops != null) filled.forEach(drops::filled);
        return level.setBlock(pos, mc, PLACE_FLAGS);
    }

    @Override
    public double[] position(UUID player, String dimension) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p == null || !p.level().dimension().identifier().toString().equals(dimension)) return null;
        return new double[]{p.getX(), p.getY(), p.getZ()};
    }

    @Override
    public boolean isCreative(UUID player) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        return p != null && p.isCreative();
    }

    @Override
    public boolean isOnline(UUID player) {
        return server.getPlayerList().getPlayer(player) != null;
    }

    @Override
    public void ding(UUID player) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p == null || p.connection == null) return;
        // Only the player who started it hears it, right where they stand.
        p.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BELL, SoundSource.PLAYERS, p.getX(), p.getY(), p.getZ(), 1.0f, 1.2f,
                p.getRandom().nextLong()));
    }
}
