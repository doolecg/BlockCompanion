package io.blockcompanion.client;

import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.nbt.NbtIO;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Saves a box of the world as a Sponge Schematic v3 ({@code .schem}) in the schematic library, with block entities and
 * entities. In singleplayer the blocks are read from the integrated server (on its thread), so chests keep their items;
 * on a server only what the client knows is saved. Chunks that aren't loaded are left out.
 */
public final class RegionSaver {
    /** Largest box saved at once. */
    public static final long MAX_VOLUME = 8L * 1024 * 1024;

    private RegionSaver() {
    }

    private record Captured(Structure structure, int missingChunks) {
    }

    /** Starts saving; the result is reported on the action bar. */
    public static void save(Box box, String typedName, boolean overwrite) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (box.volume() > MAX_VOLUME) {
            BlockCompanionClient.actionBar("Too big to save: " + box.volume() + " blocks (at most " + MAX_VOLUME + ")");
            return;
        }
        ResourceKey<Level> dimension = mc.level.dimension();
        String author = mc.getUser().getName();
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        IntegratedServer server = mc.getSingleplayerServer();
        BlockCompanionClient.actionBar("Saving...");
        ServerLevel serverLevel = server == null ? null : server.getLevel(dimension);
        if (serverLevel != null) {
            server.execute(() -> {
                try {
                    write(capture(serverLevel, box), typedName, overwrite, dataVersion, author);
                } catch (RuntimeException e) {
                    fail(e);
                }
            });
        } else {
            try {
                write(capture(mc.level, box), typedName, overwrite, dataVersion, author);
            } catch (RuntimeException e) {
                fail(e);
            }
        }
    }

    private static void write(Captured c, String typedName, boolean overwrite, int dataVersion, String author) {
        CompletableFuture.runAsync(() -> {
            try {
                String name = BlockCompanionClient.library().save(typedName, c.structure(), dataVersion, author, overwrite);
                String note = c.missingChunks() > 0 ? " (" + c.missingChunks() + " unloaded chunks left out)" : "";
                Minecraft.getInstance().execute(() -> BlockCompanionClient.actionBar(
                        "Saved " + name + ": " + c.structure().blockCount() + " blocks" + note));
                BlockCompanionClient.LOG.info("Saved {} ({} blocks, {} block entities, {} entities)", name, c.structure().blockCount(),
                        c.structure().blockEntities().size(), c.structure().entities().size());
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    private static void fail(Exception e) {
        BlockCompanionClient.LOG.warn("Could not save the schematic", e);
        Minecraft.getInstance().execute(() -> BlockCompanionClient.actionBar("Could not save: " + e.getMessage()));
    }

    /** Reads the box from a level. Runs on the level's own thread. */
    private static Captured capture(Level level, Box box) {
        Structure s = new Structure();
        Map<BlockState, io.blockcompanion.core.model.BlockState> states = new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int missing = 0;
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    missing++;
                    continue;
                }
                int x0 = Math.max(box.minX(), cx << 4), x1 = Math.min(box.maxX(), (cx << 4) + 15);
                int z0 = Math.max(box.minZ(), cz << 4), z1 = Math.min(box.maxZ(), (cz << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = box.minY(); y <= box.maxY(); y++) {
                            BlockState st = level.getBlockState(pos.set(x, y, z));
                            if (st.isAir()) continue;
                            s.set(x, y, z, states.computeIfAbsent(st, StateMapper::toCoreUncached));
                            if (!st.hasBlockEntity()) continue;
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be == null) continue;
                            try {
                                CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
                                tag.remove("x");
                                tag.remove("y");
                                tag.remove("z");
                                s.setBlockEntity(new io.blockcompanion.core.model.BlockPos(x, y, z), toCore(tag));
                            } catch (IOException | RuntimeException e) {
                                BlockCompanionClient.LOG.debug("Skipped block entity at {} {} {}: {}", x, y, z, e.toString());
                            }
                        }
                    }
                }
            }
        }
        AABB area = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        for (Entity e : level.getEntities((Entity) null, area, e -> !(e instanceof Player) && !e.isPassenger())) {
            try {
                CompoundTag tag = new CompoundTag();
                if (e.save(tag)) s.addEntity(StructureEntity.fromFullNbt(toCore(tag), 0, 0, 0));
            } catch (IOException | RuntimeException ex) {
                BlockCompanionClient.LOG.debug("Skipped entity {}: {}", e, ex.toString());
            }
        }
        return new Captured(s, missing);
    }

    /** Minecraft's compound to the core's, through the binary NBT format both speak. */
    static io.blockcompanion.core.nbt.CompoundTag toCore(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return NbtIO.read(new ByteArrayInputStream(bytes.toByteArray())).tag();
    }
}
