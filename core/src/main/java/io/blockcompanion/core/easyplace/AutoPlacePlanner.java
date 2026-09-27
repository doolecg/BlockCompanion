package io.blockcompanion.core.easyplace;

import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What easy place may click, and what its auto mode places next. Auto mode fills the missing blocks of the shown
 * schematics that are within the player's reach, one {@link EasyPlacePlanner} click at a time: bottom layer first, then
 * the nearest, only next to a real block, never over a wrong block, never a second half of a door or bed, and never the
 * same cell again until its cooldown ends. The same rules keep manual easy place from clicking doors, trapdoors and
 * chests by accident.
 */
public final class AutoPlacePlanner {
    private AutoPlacePlanner() {
    }

    /** Most blocks per second auto mode places: one a tick, the most one client tick can send. */
    public static final int MAX_RATE = 20;
    /** Blocks per second by default: slow enough for anti-cheat plugins that flag fast placing. */
    public static final int DEFAULT_RATE = 4;
    /** Ticks auto mode leaves a cell alone after trying it, placed or not: the server's answer comes in meanwhile. */
    public static final int AUTO_COOLDOWN = 40;
    /** Ticks manual easy place waits before clicking the same cell again (held right-click repeats every 4). */
    public static final int MANUAL_COOLDOWN = 10;

    /** A shown schematic in world coordinates: its box, the wanted block per cell, and whether the cell's layer shows. */
    public interface Schematic {
        Box box();

        BlockState want(int x, int y, int z);

        boolean visible(int x, int y, int z);
    }

    /** The world as the client sees it. */
    @FunctionalInterface
    public interface World {
        BlockState have(int x, int y, int z);
    }

    /** A cell to place: where, what, which schematic wants it (index into the shown list), and how far from the eye. */
    public record Cell(int x, int y, int z, BlockState want, int schematic, double distanceSq) {
    }

    /** Bottom layer first, then nearest; the rest only makes the order stable. */
    public static final Comparator<Cell> ORDER = Comparator.comparingInt(Cell::y).thenComparingDouble(Cell::distanceSq)
            .thenComparingInt(Cell::x).thenComparingInt(Cell::z);

    /**
     * The cells auto mode may place now, best first, at most {@code max}. A cell qualifies when its centre is within
     * {@code reach} of the eye, a shown schematic wants a block there on a visible layer, the world lets easy place fill
     * it ({@link #placeable}), it isn't the second half of a two-block block ({@link #secondHalf}), a real block next to it
     * holds it up ({@link #supported}), and {@code usable} accepts it (the player has the item, no cooldown). Where
     * schematics overlap, the first shown one decides.
     */
    public static List<Cell> select(double ex, double ey, double ez, double reach, List<? extends Schematic> shown, World world,
                                    Predicate<Cell> usable, int max) {
        List<Cell> out = new ArrayList<>();
        if (reach <= 0 || max <= 0) return out;
        double r2 = reach * reach;
        int x0 = (int) Math.floor(ex - reach), y0 = (int) Math.floor(ey - reach), z0 = (int) Math.floor(ez - reach);
        int x1 = (int) Math.floor(ex + reach), y1 = (int) Math.floor(ey + reach), z1 = (int) Math.floor(ez + reach);
        Set<Long> taken = new HashSet<>();
        for (int i = 0; i < shown.size(); i++) {
            Schematic s = shown.get(i);
            Box b = s.box();
            for (int y = Math.max(y0, b.minY()); y <= Math.min(y1, b.maxY()); y++) {
                for (int x = Math.max(x0, b.minX()); x <= Math.min(x1, b.maxX()); x++) {
                    for (int z = Math.max(z0, b.minZ()); z <= Math.min(z1, b.maxZ()); z++) {
                        double d = distanceSq(ex, ey, ez, x, y, z);
                        if (d > r2 || !s.visible(x, y, z)) continue;
                        BlockState want = s.want(x, y, z);
                        if (want == null || want.isAir() || !taken.add(key(x, y, z))) continue;
                        BlockState have = world.have(x, y, z);
                        if (!placeable(want, have) || secondHalf(want) || !supported(world, x, y, z)) continue;
                        Cell c = new Cell(x, y, z, want, i, d);
                        if (usable.test(c)) out.add(c);
                    }
                }
            }
        }
        out.sort(ORDER);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Squared distance from a point to the centre of a cell. */
    public static double distanceSq(double ex, double ey, double ez, int x, int y, int z) {
        double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * A cell easy place can fill: missing (the world has air, grass, water and such), or a single slab that should be a
     * double one. Never a wrong block (that needs breaking) and so never a block that would react to the click.
     */
    public static boolean placeable(BlockState want, BlockState have) {
        Compare.Result r = Compare.classify(want, have);
        if (r == Compare.Result.MISSING) return true;
        return r == Compare.Result.WRONG && "double".equals(want.get("type")) && want.name().equals(have.name())
                && !"double".equals(have.get("type"));
    }

    /**
     * The half of a two-block block that comes by itself with the other: a door's or tall plant's upper half, a bed's head.
     * Placing its item there would start a second door or bed instead.
     */
    public static boolean secondHalf(BlockState want) {
        return "upper".equals(want.get("half")) || "head".equals(want.get("part"));
    }

    /**
     * Something real holds the cell up: the cell itself (a slab to make double) or a neighbour that isn't air-like and
     * doesn't react to a click. Anti-cheat plugins flag blocks placed against nothing; a neighbour that reacts to use
     * (a door, a chest) doesn't count, so an unlucky click can never open or toggle it.
     */
    public static boolean supported(World world, int x, int y, int z) {
        if (!Compare.isAirLike(world.have(x, y, z))) return true;
        for (EasyPlacePlanner.Dir d : EasyPlacePlanner.Dir.values()) {
            BlockState n = world.have(x + d.dx, y + d.dy, z + d.dz);
            if (n != null && !Compare.isAirLike(n) && !reactsToUse(n)) return true;
        }
        return false;
    }

    private static final Set<String> REACTS = Set.of("lever", "repeater", "comparator", "note_block", "barrel", "smoker", "crafting_table",
            "crafter", "cartography_table", "smithing_table", "stonecutter", "loom", "grindstone", "enchanting_table", "brewing_stand",
            "beacon", "hopper", "dispenser", "dropper", "lectern", "bell", "cake", "daylight_detector", "jukebox", "respawn_anchor",
            "composter", "flower_pot", "chiseled_bookshelf", "decorated_pot", "redstone_wire", "dragon_egg", "command_block",
            "chain_command_block", "repeating_command_block", "structure_block", "jigsaw", "test_block", "test_instance_block", "vault",
            "cauldron", "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "end_portal_frame", "shelf");

    /**
     * A block that does something when right-clicked: toggles (doors, trapdoors, gates, buttons, levers, repeaters,
     * comparators, note blocks), opens a screen (chests, barrels, furnaces, shulker boxes, crafting and work tables),
     * or takes or gives an item (pots, cakes, beds...). Easy place never clicks one.
     */
    public static boolean reactsToUse(BlockState s) {
        if (s == null || s.isAir()) return false;
        String p = s.path();
        if (REACTS.contains(p) || p.startsWith("potted_")) return true;
        return p.endsWith("_door") || p.endsWith("_trapdoor") || p.endsWith("_fence_gate") || p.endsWith("_button") || p.endsWith("_bed")
                || p.endsWith("shulker_box") || p.endsWith("chest") || p.endsWith("furnace") || p.endsWith("anvil") || p.endsWith("_sign")
                || p.endsWith("candle_cake") || p.endsWith("_shelf") || p.endsWith("copper_golem_statue");
    }

    /** Blocks per second turned into ticks between blocks, capped by a server's limit ({@code serverRate} &gt; 0). */
    public static int intervalTicks(int blocksPerSecond, int serverRate) {
        int rate = Math.max(1, Math.min(MAX_RATE, blocksPerSecond));
        if (serverRate > 0) rate = Math.min(rate, serverRate);
        return (int) Math.ceil(20.0 / rate);
    }

    /** The player's reach, or a server's auto-place range when that is shorter ({@code serverRange} &gt; 0). */
    public static double reach(double playerReach, int serverRange) {
        return serverRange > 0 ? Math.min(playerReach, serverRange) : playerReach;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    /** Cells tried a moment ago, left alone until their time is up so nothing is clicked over and over. */
    public static final class Cooldowns {
        private final Map<Long, Long> until = new HashMap<>();

        /** Cell {@code (x, y, z)} was clicked at {@code tick}: leave it for {@code ticks}. */
        public void tried(int x, int y, int z, long tick, int ticks) {
            until.put(key(x, y, z), tick + ticks);
            if (until.size() > 4096) prune(tick);
        }

        public boolean cooling(int x, int y, int z, long tick) {
            Long u = until.get(key(x, y, z));
            return u != null && tick < u;
        }

        public void prune(long tick) {
            until.values().removeIf(u -> u <= tick);
        }

        public void clear() {
            until.clear();
        }

        public int size() {
            return until.size();
        }
    }
}
