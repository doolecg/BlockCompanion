package io.blockcompanion.core.items;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.placement.Placement;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds up the items blocks cost, using {@link Items}' survival rules. Ported from Resource Tracker's {@code Tally}, with
 * a placement in the world instead of BlockDesigner layers.
 */
public final class ItemCount {
    private ItemCount() {
    }

    /** An item the build needs: how many, and a block that shows it (for its icon), or null. */
    public record Need(String item, long count, BlockState icon) {
    }

    /** The items a set of block states (with how many of each) and entities cost, most first. */
    public static List<Need> count(Map<BlockState, Long> states, Collection<StructureEntity> entities, boolean mobs) {
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, BlockState> icons = new HashMap<>();
        states.forEach((st, n) -> Items.forBlock(st).forEach((item, per) -> {
            totals.merge(item, per * n, Long::sum);
            // The block placed by the item shows it: its own id when it is a block, else the block it came from.
            BlockState icon = item.equals(st.name()) ? BlockState.of(st.name()) : st;
            icons.merge(item, icon, (a, b) -> a.name().equals(item) ? a : b);
        }));
        for (StructureEntity e : entities) Items.forEntity(e, mobs).forEach((item, n) -> totals.merge(item, (long) n, Long::sum));
        return totals.entrySet().stream()
                .map(e -> new Need(e.getKey(), e.getValue(), icons.get(e.getKey())))
                .sorted((a, b) -> a.count() != b.count() ? Long.compare(b.count(), a.count()) : a.item().compareTo(b.item()))
                .toList();
    }

    /**
     * The items a whole placement costs, or only the part inside {@code within} (world coordinates) when it is not
     * null. Mobs count as spawn eggs when {@code mobs} is set.
     */
    public static List<Need> count(Placement p, Box within, boolean mobs) {
        Map<BlockState, Long> states = new HashMap<>();
        if (within == null) {
            // Item costs don't depend on rotation, so the untransformed palette counts will do.
            states.putAll(p.structure().stateCounts());
        } else {
            Box b = p.worldBox();
            int x0 = Math.max(within.minX(), b.minX()), y0 = Math.max(within.minY(), b.minY()), z0 = Math.max(within.minZ(), b.minZ());
            int x1 = Math.min(within.maxX(), b.maxX()), y1 = Math.min(within.maxY(), b.maxY()), z1 = Math.min(within.maxZ(), b.maxZ());
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        BlockPos l = p.toLocal(x, y, z);
                        BlockState st = p.structure().get(l.x(), l.y(), l.z());
                        if (!st.isAir()) states.merge(st, 1L, Long::sum);
                    }
                }
            }
        }
        List<StructureEntity> entities = p.structure().entities().stream().filter(e -> {
            if (within == null) return true;
            BlockPos w = p.toWorld((int) Math.floor(e.x()), (int) Math.floor(e.y()), (int) Math.floor(e.z()));
            return within.contains(w.x(), w.y(), w.z());
        }).toList();
        return count(states, entities, mobs);
    }

    /**
     * One row of the in-game resource list: how many the build still needs, how many the player carries, and how
     * many are still missing after that.
     */
    public record Row(String item, long needed, long have, BlockState icon) {
        public long missing() {
            return Math.max(0, needed - have);
        }
    }

    /**
     * Joins what is needed with what the player has (item id to count), sorted by most missing, then most needed,
     * then name.
     */
    public static List<Row> rows(List<Need> needs, Map<String, Long> inventory) {
        return needs.stream()
                .map(n -> new Row(n.item(), n.count(), inventory.getOrDefault(n.item(), 0L), n.icon()))
                .sorted((a, b) -> {
                    if (a.missing() != b.missing()) return Long.compare(b.missing(), a.missing());
                    if (a.needed() != b.needed()) return Long.compare(b.needed(), a.needed());
                    return a.item().compareTo(b.item());
                })
                .toList();
    }
}
