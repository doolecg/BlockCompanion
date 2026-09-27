package io.blockcompanion.paper;

import io.blockcompanion.core.autobuild.BuildWorld;
import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.model.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AutoBuild's world through the Bukkit API: block data is set straight into the world with physics on; nothing is
 * used or clicked, so doors and trapdoors keep the state the schematic gives them and no container opens. Main
 * thread only.
 */
final class PaperBuildWorld implements BuildWorld {
    /** Core state to Bukkit block data; a block this server doesn't have maps to the empty Optional. */
    private final Map<BlockState, Optional<BlockData>> toData = new HashMap<>();

    private BlockData data(BlockState s) {
        return toData.computeIfAbsent(s, PaperBuildWorld::resolve).orElse(null);
    }

    private static Optional<BlockData> resolve(BlockState s) {
        try {
            return Optional.of(Bukkit.createBlockData(s.toString()));
        } catch (IllegalArgumentException e) {
            // A property this version doesn't know: keep the ones it does.
        }
        BlockData d;
        try {
            d = Bukkit.createBlockData(s.name());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        for (var p : s.properties().entrySet()) {
            try {
                d = d.merge(Bukkit.createBlockData(s.name() + "[" + p.getKey() + "=" + p.getValue() + "]"));
            } catch (IllegalArgumentException e) {
                // Skip it.
            }
        }
        return Optional.of(d);
    }

    private static Block block(String dimension, int x, int y, int z) {
        World w = PaperChestAccess.world(dimension);
        if (w == null || y < w.getMinHeight() || y >= w.getMaxHeight()) return null;
        return w.getBlockAt(x, y, z);
    }

    @Override
    public boolean dimensionExists(String dimension) {
        return PaperChestAccess.world(dimension) != null;
    }

    @Override
    public boolean isLoaded(String dimension, int x, int y, int z) {
        World w = PaperChestAccess.world(dimension);
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public BlockState get(String dimension, int x, int y, int z) {
        Block b = block(dimension, x, y, z);
        return b == null ? BlockState.AIR : BlockState.parse(b.getBlockData().getAsString());
    }

    @Override
    public Check check(String dimension, int x, int y, int z, BlockState state) {
        Block b = block(dimension, x, y, z);
        BlockData d = data(state);
        if (b == null || d == null) return Check.UNKNOWN_BLOCK;
        // Sand and gravel over a gap would fall; torches, plants, rails and the like need what they stand on.
        Block below = b.getRelative(0, -1, 0);
        if (d.getMaterial().hasGravity() && (below.isEmpty() || below.isLiquid())) return Check.UNSUPPORTED;
        if (!d.isSupported(b)) return Check.UNSUPPORTED;
        return Check.OK;
    }

    @Override
    public boolean place(String dimension, int x, int y, int z, BlockState state) {
        Block b = block(dimension, x, y, z);
        BlockData d = data(state);
        if (b == null || d == null) return false;
        b.setBlockData(d, false);
        return true;
    }

    @Override
    public Removal removal(String dimension, int x, int y, int z) {
        Block b = block(dimension, x, y, z);
        if (b == null || b.isEmpty()) return Removal.NEVER;
        Material m = b.getType();
        // Bedrock, barriers, portals, command and structure blocks can't be broken by hand: never by AutoBuild either.
        if (m.getHardness() < 0) return Removal.NEVER;
        if (b.getState() instanceof TileState) return Removal.OTHER;
        return m.isSolid() && fullCube(b) ? Removal.SOLID : Removal.OTHER;
    }

    /** True when the block's collision is one whole cube (not a slab, stair, fence or wall). */
    private static boolean fullCube(Block b) {
        Collection<BoundingBox> boxes = b.getCollisionShape().getBoundingBoxes();
        if (boxes.size() != 1) return false;
        BoundingBox box = boxes.iterator().next();
        return box.getVolume() >= 0.999;
    }

    @Override
    public boolean replace(String dimension, int x, int y, int z, BlockState state, Drops drops) {
        Block b = block(dimension, x, y, z);
        BlockData d = state.isAir() ? Material.AIR.createBlockData() : data(state);
        if (b == null || d == null) return false;
        List<ItemStack> stacks = new ArrayList<>();
        // What a container held, always (Bukkit would delete it when the block is set), taken out first: a shulker box
        // then drops empty instead of with a copy of it...
        if (b.getState() instanceof Container c) {
            Inventory inv = c instanceof Chest chest ? chest.getBlockInventory() : c.getInventory();
            for (ItemStack st : inv.getContents()) if (st != null && !st.getType().isAir()) stacks.add(st.clone());
            inv.clear();
        }
        // ...and what breaking it by hand would drop (nothing in creative).
        if (drops != null) stacks.addAll(b.getDrops());
        LinkedHashSet<LinkedChests.Pos> filled = new LinkedHashSet<>();
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack st : stacks) {
            ItemStack left = st;
            // Into the linked chests in order (survival), the rest on the ground.
            if (drops != null) {
                for (LinkedChests.Pos c : drops.chests()) {
                    if (left == null) break;
                    int before = left.getAmount();
                    left = PaperChestAccess.insert(c.dimension(), c.x(), c.y(), c.z(), left);
                    if (left == null || left.getAmount() != before) filled.add(c);
                }
            }
            if (left != null && left.getAmount() > 0) b.getWorld().dropItemNaturally(at, left);
        }
        if (drops != null) filled.forEach(drops::filled);
        b.setBlockData(d, false);
        return true;
    }

    @Override
    public double[] position(UUID player, String dimension) {
        Player p = Bukkit.getPlayer(player);
        World w = PaperChestAccess.world(dimension);
        if (p == null || w == null || !p.getWorld().equals(w)) return null;
        Location l = p.getLocation();
        return new double[]{l.getX(), l.getY(), l.getZ()};
    }

    @Override
    public boolean isCreative(UUID player) {
        Player p = Bukkit.getPlayer(player);
        return p != null && p.getGameMode() == GameMode.CREATIVE;
    }

    @Override
    public boolean isOnline(UUID player) {
        return Bukkit.getPlayer(player) != null;
    }

    @Override
    public void ding(UUID player) {
        Player p = Bukkit.getPlayer(player);
        // Only the player who started it hears it, right where they stand.
        if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }
}
