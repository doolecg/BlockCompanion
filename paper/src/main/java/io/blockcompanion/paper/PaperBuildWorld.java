package io.blockcompanion.paper;

import io.blockcompanion.core.autobuild.BuildWorld;
import io.blockcompanion.core.model.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.HashMap;
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
        b.setBlockData(d, true);
        return true;
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
