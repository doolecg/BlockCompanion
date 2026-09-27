package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.transform.BlockTransformer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * What AutoBuild does with a placement, worked out before a block goes down: every schematic block as a {@link Step}
 * in build order, the items each step takes, and whether the linked chests hold enough.
 *
 * <p><b>Order.</b> Layer by layer from the lowest y up. Within a layer, blocks that stand on their own come first and
 * blocks that hang on a neighbour (torches and signs on walls, buttons, levers, ladders) after them, each group by z then
 * x. Blocks that hang from the block above (a hanging lantern, hanging sign or vines) wait for the layer above.
 *
 * <p><b>Two-block structures.</b> A door, tall plant or bed is one step: its lower half (or foot) carries the other
 * half as a partner, is counted once and costs one item. The other half's own position is not a step of its own.
 */
public final class AutoBuildPlan {
    private AutoBuildPlan() {
    }

    /** What kind of step it is. */
    public enum Kind {
        /** Placed from its items. */
        NORMAL,
        /** A block no item places (fire, portals, piston heads, flowing fluids, a half without its other half): skipped. */
        FREE,
        /** A fluid source (water, lava, powder snow): AutoBuild doesn't pour buckets, so it is skipped. */
        FLUID
    }

    /** One block in world coordinates, with the state the schematic wants there (already turned with the placement). */
    public record Cell(int x, int y, int z, BlockState state) {
    }

    /**
     * One thing AutoBuild places: the {@code main} block, plus the other half of a two-block structure placed with it.
     *
     * @param items what it costs (item id to count); empty for free steps
     * @param order the step's layer for ordering (its y, or one more for blocks hanging from above)
     */
    public record Step(Cell main, List<Cell> partners, Map<String, Integer> items, Kind kind, int order, boolean attached) {
        public Step {
            partners = List.copyOf(partners);
            items = Map.copyOf(items);
        }

        /** Every cell of the step, the main one first. */
        public List<Cell> cells() {
            if (partners.isEmpty()) return List.of(main);
            List<Cell> all = new ArrayList<>(partners.size() + 1);
            all.add(main);
            all.addAll(partners);
            return all;
        }
    }

    /** Blocks that hang on a side neighbour, so they go after the rest of their layer. */
    private static final Set<String> SIDE_ATTACHED = Set.of("ladder", "lever", "tripwire_hook", "cocoa", "vine", "glow_lichen",
            "sculk_vein", "resin_clump", "redstone_wall_torch", "soul_wall_torch", "wall_torch", "copper_wall_torch");

    /** Blocks that hang from the block above. */
    private static final Set<String> HANGING = Set.of("cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant",
            "spore_blossom", "hanging_roots", "pale_hanging_moss");

    /** Every schematic block of the placement, in build order. */
    public static List<Step> plan(Placement p) {
        Map<Long, Cell> cells = new HashMap<>();
        List<Cell> order = new ArrayList<>();
        p.structure().forEachBlock((x, y, z, s) -> {
            if (s.isAir() || s == BlockState.STRUCTURE_VOID) return;
            BlockPos w = p.toWorld(x, y, z);
            BlockState turned = BlockTransformer.defaults().apply(s, p.transform());
            Cell c = new Cell(w.x(), w.y(), w.z(), turned);
            cells.put(BlockPos.pack(w.x(), w.y(), w.z()), c);
            order.add(c);
        });
        return plan(cells, order);
    }

    /** Plans loose cells (world coordinates, turned states); what {@link #plan(Placement)} does after reading the schematic. */
    public static List<Step> plan(List<Cell> list) {
        Map<Long, Cell> cells = new HashMap<>();
        for (Cell c : list) cells.put(BlockPos.pack(c.x(), c.y(), c.z()), c);
        return plan(cells, list);
    }

    private static List<Step> plan(Map<Long, Cell> cells, List<Cell> order) {
        // Second halves that ride along with their first half, so they aren't steps of their own.
        Map<Long, Cell> taken = new HashMap<>();
        Map<Cell, Cell> partnerOf = new LinkedHashMap<>();
        for (Cell c : order) {
            Cell other = partner(c, cells);
            if (other == null) continue;
            partnerOf.put(c, other);
            taken.put(BlockPos.pack(other.x(), other.y(), other.z()), other);
        }
        List<Step> steps = new ArrayList<>(order.size());
        for (Cell c : order) {
            if (taken.containsKey(BlockPos.pack(c.x(), c.y(), c.z()))) continue;
            Cell other = partnerOf.get(c);
            BlockState s = c.state();
            Map<String, Integer> items = Items.forBlock(s);
            Kind kind = items.isEmpty() ? Kind.FREE : isFluid(s) ? Kind.FLUID : Kind.NORMAL;
            int ord = c.y() + (hangsFromAbove(s) ? 1 : 0);
            steps.add(new Step(c, other == null ? List.of() : List.of(other), kind == Kind.NORMAL ? items : Map.of(), kind, ord,
                    attached(s) || hangsFromAbove(s)));
        }
        steps.sort(ORDER);
        return steps;
    }

    /** Build order: layer, then free-standing before attached, then z, then x. */
    public static final Comparator<Step> ORDER = Comparator.comparingInt(Step::order)
            .thenComparing(Step::attached)
            .thenComparingInt(s -> s.main().z())
            .thenComparingInt(s -> s.main().x())
            .thenComparingInt(s -> s.main().y());

    /**
     * The second half of a two-block structure that {@code c} is the first half of, or null: the upper half above a
     * lower half (doors, tall flowers, small dripleaf, pitcher plants), or a bed's head beside its foot.
     */
    static Cell partner(Cell c, Map<Long, Cell> cells) {
        BlockState s = c.state();
        if ("lower".equals(s.get("half"))) {
            Cell up = cells.get(BlockPos.pack(c.x(), c.y() + 1, c.z()));
            if (up != null && up.state().name().equals(s.name()) && "upper".equals(up.state().get("half"))) return up;
            return null;
        }
        if ("foot".equals(s.get("part")) && s.has("facing")) {
            int[] d = offset(s.get("facing"));
            if (d == null) return null;
            Cell head = cells.get(BlockPos.pack(c.x() + d[0], c.y(), c.z() + d[1]));
            if (head != null && head.state().name().equals(s.name()) && "head".equals(head.state().get("part"))) return head;
        }
        return null;
    }

    private static int[] offset(String facing) {
        return switch (facing) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "west" -> new int[]{-1, 0};
            case "east" -> new int[]{1, 0};
            default -> null;
        };
    }

    private static boolean isFluid(BlockState s) {
        for (String item : Items.forBlock(s).keySet()) if (item.endsWith("_bucket")) return true;
        return false;
    }

    /** True for blocks that hang on a side neighbour (and so go after the rest of their layer). */
    public static boolean attached(BlockState s) {
        String p = s.path();
        if (SIDE_ATTACHED.contains(p) || p.contains("_wall_") || p.endsWith("_button")) return true;
        return s.has("face") && !"floor".equals(s.get("face"));
    }

    /** True for blocks that hang from the block above (and so wait for the layer above). */
    public static boolean hangsFromAbove(BlockState s) {
        String p = s.path();
        if (HANGING.contains(p)) return true;
        if ("true".equals(s.get("hanging")) && (p.endsWith("lantern"))) return true;
        if (p.endsWith("_hanging_sign") && !p.contains("_wall_")) return true;
        if (p.equals("pointed_dripstone") && "down".equals(s.get("vertical_direction"))) return true;
        return s.has("face") && "ceiling".equals(s.get("face"));
    }

    // ---- need against have ------------------------------------------------------------------------------------------

    /** The items the steps {@code toPlace} accepts still take, added up (item id to count). Free and fluid steps take nothing. */
    public static Map<String, Long> required(List<Step> steps, Predicate<Step> toPlace) {
        Map<String, Long> need = new TreeMap<>();
        for (Step s : steps) {
            if (s.kind() != Kind.NORMAL || !toPlace.test(s)) continue;
            s.items().forEach((item, n) -> need.merge(item, (long) n, Long::sum));
        }
        return need;
    }

    /** How many steps {@code toPlace} accepts (the blocks AutoBuild would place, a door or bed counting once). */
    public static long count(List<Step> steps, Predicate<Step> toPlace) {
        long n = 0;
        for (Step s : steps) if (s.kind() == Kind.NORMAL && toPlace.test(s)) n++;
        return n;
    }

    /** What is missing: for each item, how many more {@code need} asks for than {@code have} holds. Empty when everything is there. */
    public static Map<String, Long> shortfall(Map<String, Long> need, Map<String, Long> have) {
        Map<String, Long> out = new TreeMap<>();
        need.forEach((item, n) -> {
            long got = have.getOrDefault(item, 0L);
            if (got < n) out.put(item, n - got);
        });
        return out;
    }

    /** "Short: 12 oak planks, 3 glass", most missing first, at most {@code max} items named (then "and 4 more"). */
    public static String describeShort(Map<String, Long> shortfall, int max) {
        if (shortfall.isEmpty()) return "";
        List<Map.Entry<String, Long>> list = new ArrayList<>(shortfall.entrySet());
        list.sort((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : Long.compare(b.getValue(), a.getValue()));
        StringBuilder b = new StringBuilder("Short: ");
        int shown = Math.min(Math.max(1, max), list.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) b.append(", ");
            b.append(String.format(Locale.ROOT, "%,d", list.get(i).getValue())).append(' ')
                    .append(Items.pretty(list.get(i).getKey()).toLowerCase(Locale.ROOT));
        }
        if (list.size() > shown) b.append(" and ").append(list.size() - shown).append(" more");
        return b.toString();
    }

    /**
     * Whether a step's block still needs placing, given what is in the world at its main cell: the spot is empty (or
     * holds only grass, water and similar). A correct block (a door open or shut, a powered trapdoor all count) and a
     * wrong block both need nothing: AutoBuild never breaks blocks.
     */
    public static boolean needsPlacing(Step s, BlockState inWorld) {
        return Compare.classify(s.main().state(), inWorld) == Compare.Result.MISSING;
    }
}
