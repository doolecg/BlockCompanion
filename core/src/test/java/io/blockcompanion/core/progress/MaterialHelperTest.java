package io.blockcompanion.core.progress;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The material helper's query: the cells still needing the held item, nearest first. */
class MaterialHelperTest {
    static final BlockState PLANKS = BlockState.of("minecraft:oak_planks");
    static final BlockState STONE = BlockState.of("minecraft:stone");

    /** A 40-block line of planks along x on level 0, stone on level 1 above the first 5, a wall torch on level 0 at x=40. */
    static Placement line() {
        Structure s = new Structure();
        for (int x = 0; x < 40; x++) s.set(x, 0, 0, PLANKS);
        for (int x = 0; x < 5; x++) s.set(x, 1, 0, STONE);
        s.set(40, 0, 0, BlockState.parse("wall_torch[facing=north]"));
        return new Placement("line.schem", s, new BlockPos(0, 64, 0));
    }

    static ProgressTracker scanned(Placement p) {
        ProgressTracker t = new ProgressTracker(p);
        for (long k : t.sectionKeys()) {
            BlockPos s = BlockPos.unpack(k);
            t.scanSection(s.x(), s.y(), s.z(), (x, y, z) -> BlockState.AIR);
        }
        return t;
    }

    @Test
    void nearestCellsFirstWithinTheLimit() {
        ProgressTracker t = scanned(line());
        List<BlockPos> near = t.nearestMissing("minecraft:oak_planks", 20.5, 65, 0.5, 64, 3, l -> true);
        assertThat(near).containsExactly(new BlockPos(20, 64, 0), new BlockPos(19, 64, 0), new BlockPos(21, 64, 0));
    }

    @Test
    void placedCellsAndOtherItemsAreLeftOut() {
        ProgressTracker t = scanned(line());
        t.set(20, 64, 0, PLANKS);
        List<BlockPos> near = t.nearestMissing("minecraft:oak_planks", 20.5, 65, 0.5, 64, 2, l -> true);
        assertThat(near).doesNotContain(new BlockPos(20, 64, 0)).hasSize(2);
        assertThat(t.nearestMissing("minecraft:stone", 20.5, 65, 0.5, 64, 10, l -> true)).hasSize(5).allMatch(p -> p.y() == 65);
        assertThat(t.nearestMissing("minecraft:diamond_block", 0, 64, 0, 64, 10, l -> true)).isEmpty();
        assertThat(t.item("minecraft:oak_planks").left()).isEqualTo(39);
    }

    @Test
    void wallVariantsNeedTheirStandingItem() {
        ProgressTracker t = scanned(line());
        assertThat(t.nearestMissing("minecraft:torch", 40, 64, 0, 16, 5, l -> true)).containsExactly(new BlockPos(40, 64, 0));
    }

    @Test
    void radiusAndLayerFilterApply() {
        ProgressTracker t = scanned(line());
        assertThat(t.nearestMissing("minecraft:oak_planks", 0.5, 64.5, 0.5, 2.9, 100, l -> true)).hasSize(3);
        // Only level 1 visible: no planks there.
        assertThat(t.nearestMissing("minecraft:oak_planks", 0.5, 64.5, 0.5, 64, 100, l -> l == 1)).isEmpty();
        assertThat(t.nearestMissing("minecraft:stone", 0.5, 64.5, 0.5, 64, 100, l -> l == 1)).hasSize(5);
    }

    @Test
    void cellsNeverSeenAreNotOffered() {
        // Unknown (never loaded) cells may already be built: the helper only points at cells known to be missing.
        ProgressTracker t = new ProgressTracker(line());
        assertThat(t.nearestMissing("minecraft:oak_planks", 0, 64, 0, 64, 5, l -> true)).isEmpty();
    }
}
