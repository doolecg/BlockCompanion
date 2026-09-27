package io.blockcompanion.core.easyplace;

import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.easyplace.AutoPlacePlanner.Cell;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class AutoPlacePlannerTest {
    private static BlockState s(String text) {
        return BlockState.parse(text);
    }

    private static final BlockState STONE = s("stone");

    /** A schematic from a map of cells, with a lowest visible layer. */
    private record Schem(Box box, Map<Long, BlockState> cells, int topVisibleY) implements AutoPlacePlanner.Schematic {
        public BlockState want(int x, int y, int z) {
            return cells.getOrDefault(AutoPlacePlanner.key(x, y, z), BlockState.AIR);
        }

        public boolean visible(int x, int y, int z) {
            return y <= topVisibleY;
        }
    }

    /** A world of stone at y = 63 and below and air above, with a few blocks set. */
    private static final class World implements AutoPlacePlanner.World {
        final Map<Long, BlockState> set = new HashMap<>();

        public BlockState have(int x, int y, int z) {
            BlockState b = set.get(AutoPlacePlanner.key(x, y, z));
            if (b != null) return b;
            return y <= 63 ? STONE : BlockState.AIR;
        }

        void put(int x, int y, int z, BlockState b) {
            set.put(AutoPlacePlanner.key(x, y, z), b);
        }
    }

    private static Schem cube(int x0, int y0, int z0, int size, BlockState b) {
        Map<Long, BlockState> cells = new HashMap<>();
        for (int x = x0; x < x0 + size; x++)
            for (int y = y0; y < y0 + size; y++)
                for (int z = z0; z < z0 + size; z++) cells.put(AutoPlacePlanner.key(x, y, z), b);
        return new Schem(new Box(x0, y0, z0, x0 + size - 1, y0 + size - 1, z0 + size - 1), cells, Integer.MAX_VALUE);
    }

    private static final Predicate<Cell> ANY = c -> true;

    private static List<Cell> select(double reach, List<Schem> shown, World w, Predicate<Cell> usable) {
        return AutoPlacePlanner.select(0.5, 65.62, 0.5, reach, shown, w, usable, 1000);
    }

    @Test
    void placesTheBottomLayerFirstThenTheNearest() {
        World w = new World();
        List<Cell> cells = select(4.5, List.of(cube(-2, 64, -2, 5, STONE)), w, ANY);
        assertThat(cells).isNotEmpty();
        // Only the bottom layer stands on something; the layers above have nothing real next to them yet.
        assertThat(cells).allMatch(c -> c.y() == 64);
        assertThat(cells.get(0)).extracting(Cell::x, Cell::z).containsExactly(0, 0);
        for (int i = 1; i < cells.size(); i++) assertThat(cells.get(i).distanceSq()).isGreaterThanOrEqualTo(cells.get(i - 1).distanceSq());
    }

    @Test
    void goesUpALayerOncePlacedBlocksHoldItUp() {
        World w = new World();
        w.put(3, 64, 0, STONE);
        List<Cell> cells = select(4.5, List.of(cube(3, 64, 0, 1, STONE), cube(3, 65, 0, 1, STONE)), w, ANY);
        assertThat(cells).extracting(Cell::y).containsExactly(65);
    }

    @Test
    void staysWithinReach() {
        World w = new World();
        List<Cell> cells = select(3, List.of(cube(-8, 64, -8, 17, STONE)), w, ANY);
        assertThat(cells).isNotEmpty();
        assertThat(cells).allMatch(c -> c.distanceSq() <= 9);
        assertThat(select(0, List.of(cube(-8, 64, -8, 17, STONE)), w, ANY)).isEmpty();
    }

    @Test
    void neverOverAWrongBlockButFillsAHalfSlab() {
        World w = new World();
        w.put(1, 64, 0, s("dirt"));
        w.put(2, 64, 0, s("stone_slab[type=bottom]"));
        w.put(3, 64, 0, s("short_grass"));
        Map<Long, BlockState> cells = new HashMap<>();
        cells.put(AutoPlacePlanner.key(1, 64, 0), STONE);
        cells.put(AutoPlacePlanner.key(2, 64, 0), s("stone_slab[type=double]"));
        cells.put(AutoPlacePlanner.key(3, 64, 0), STONE);
        Schem sc = new Schem(new Box(1, 64, 0, 3, 64, 0), cells, Integer.MAX_VALUE);
        assertThat(select(4.5, List.of(sc), w, ANY)).extracting(Cell::x).containsExactly(2, 3);
    }

    @Test
    void onlyVisibleLayersAndOnlyWhatIsUsable() {
        World w = new World();
        Schem low = new Schem(new Box(1, 64, 0, 1, 64, 0), Map.of(AutoPlacePlanner.key(1, 64, 0), STONE), 63);
        assertThat(select(4.5, List.of(low), w, ANY)).isEmpty();
        List<Cell> cells = select(4.5, List.of(cube(-1, 64, -1, 3, STONE)), w, c -> c.x() == 1);
        assertThat(cells).isNotEmpty().allMatch(c -> c.x() == 1);
    }

    @Test
    void theFirstShownSchematicDecidesOverlaps() {
        World w = new World();
        List<Cell> cells = select(4.5, List.of(cube(0, 64, 0, 1, s("oak_planks")), cube(0, 64, 0, 1, STONE)), w, ANY);
        assertThat(cells).hasSize(1);
        assertThat(cells.get(0).schematic()).isZero();
        assertThat(cells.get(0).want()).isEqualTo(s("oak_planks"));
    }

    @Test
    void neverTheUpperHalfOfADoorNorTheHeadOfABed() {
        assertThat(AutoPlacePlanner.secondHalf(s("oak_door[facing=north,half=upper,hinge=left,open=false]"))).isTrue();
        assertThat(AutoPlacePlanner.secondHalf(s("tall_grass[half=upper]"))).isTrue();
        assertThat(AutoPlacePlanner.secondHalf(s("red_bed[facing=north,part=head]"))).isTrue();
        assertThat(AutoPlacePlanner.secondHalf(s("oak_door[facing=north,half=lower,hinge=left,open=false]"))).isFalse();
        assertThat(AutoPlacePlanner.secondHalf(s("red_bed[facing=north,part=foot]"))).isFalse();
        assertThat(AutoPlacePlanner.secondHalf(s("oak_stairs[facing=north,half=top]"))).isFalse();
        assertThat(AutoPlacePlanner.secondHalf(s("oak_trapdoor[facing=north,half=top]"))).isFalse();

        World w = new World();
        Map<Long, BlockState> cells = new HashMap<>();
        cells.put(AutoPlacePlanner.key(1, 64, 0), s("oak_door[facing=north,half=lower,hinge=left]"));
        cells.put(AutoPlacePlanner.key(1, 65, 0), s("oak_door[facing=north,half=upper,hinge=left]"));
        Schem door = new Schem(new Box(1, 64, 0, 1, 65, 0), cells, Integer.MAX_VALUE);
        // With the lower half missing the upper one has a neighbour too once anything stands next to it; still skipped.
        w.put(2, 65, 0, STONE);
        assertThat(select(4.5, List.of(door), w, ANY)).extracting(Cell::y).containsExactly(64);
    }

    @Test
    void anOpenOrPoweredDoorCountsAsPlacedAndIsLeftAlone() {
        BlockState want = s("oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        BlockState opened = s("oak_door[facing=north,half=lower,hinge=left,open=true,powered=true]");
        assertThat(Compare.classify(want, opened)).isEqualTo(Compare.Result.CORRECT);
        assertThat(AutoPlacePlanner.placeable(want, opened)).isFalse();
        BlockState trap = s("oak_trapdoor[facing=east,half=top,open=false,powered=false,waterlogged=false]");
        assertThat(AutoPlacePlanner.placeable(trap, trap.with("open", "true"))).isFalse();
        // A door the wrong way round is wrong, and easy place never clicks it.
        assertThat(AutoPlacePlanner.placeable(want, want.with("hinge", "right"))).isFalse();
    }

    @Test
    void blocksThatReactToUseAreKnown() {
        for (String b : new String[]{"oak_door[half=lower]", "iron_trapdoor", "oak_fence_gate", "stone_button", "lever", "repeater",
                "comparator", "note_block", "red_bed", "chest", "trapped_chest", "ender_chest", "barrel", "furnace", "blast_furnace",
                "smoker", "crafting_table", "shulker_box", "blue_shulker_box", "hopper", "dispenser", "anvil", "chipped_anvil", "oak_sign",
                "oak_wall_hanging_sign", "flower_pot", "potted_poppy", "cake", "lectern", "enchanting_table", "brewing_stand"}) {
            assertThat(AutoPlacePlanner.reactsToUse(s(b))).as(b).isTrue();
        }
        for (String b : new String[]{"stone", "oak_planks", "oak_stairs", "stone_slab", "oak_log", "glass", "air", "oak_fence",
                "observer", "piston", "redstone_block", "torch"}) {
            assertThat(AutoPlacePlanner.reactsToUse(s(b))).as(b).isFalse();
        }
    }

    @Test
    void aBlockThatReactsToUseDoesNotHoldACellUp() {
        World w = new World();
        // A cell high above the ground, whose only neighbour is a chest, a door or a trapdoor: skipped.
        for (String b : new String[]{"chest[facing=north]", "oak_door[half=lower,facing=north]", "oak_trapdoor[half=top,facing=north]"}) {
            w.put(0, 70, 1, s(b));
            assertThat(AutoPlacePlanner.supported(w, 0, 71, 1)).as(b).isFalse();
        }
        w.put(0, 70, 1, s("oak_planks"));
        assertThat(AutoPlacePlanner.supported(w, 0, 71, 1)).isTrue();
        // Nothing next to it at all.
        assertThat(AutoPlacePlanner.supported(w, 5, 80, 5)).isFalse();
        // A single slab supports its own second half.
        w.put(5, 80, 5, s("stone_slab[type=bottom]"));
        assertThat(AutoPlacePlanner.supported(w, 5, 80, 5)).isTrue();
    }

    @Test
    void cooldownsKeepACellAloneForAWhile() {
        AutoPlacePlanner.Cooldowns cd = new AutoPlacePlanner.Cooldowns();
        cd.tried(1, 64, -3, 100, AutoPlacePlanner.AUTO_COOLDOWN);
        assertThat(cd.cooling(1, 64, -3, 100)).isTrue();
        assertThat(cd.cooling(1, 64, -3, 139)).isTrue();
        assertThat(cd.cooling(1, 64, -3, 140)).isFalse();
        assertThat(cd.cooling(1, 65, -3, 100)).isFalse();
        assertThat(cd.cooling(-1, 64, 3, 100)).isFalse();
        cd.tried(-30_000_000, -64, 30_000_000, 100, 5);
        assertThat(cd.cooling(-30_000_000, -64, 30_000_000, 102)).isTrue();
        cd.prune(120);
        assertThat(cd.size()).isEqualTo(1);
        cd.clear();
        assertThat(cd.cooling(1, 64, -3, 100)).isFalse();
    }

    @Test
    void rateAndReachStayWithinLimits() {
        assertThat(AutoPlacePlanner.intervalTicks(AutoPlacePlanner.DEFAULT_RATE, 0)).isEqualTo(5);
        assertThat(AutoPlacePlanner.intervalTicks(20, 0)).isEqualTo(1);
        assertThat(AutoPlacePlanner.intervalTicks(1000, 0)).isEqualTo(1);
        assertThat(AutoPlacePlanner.intervalTicks(0, 0)).isEqualTo(20);
        assertThat(AutoPlacePlanner.intervalTicks(20, 2)).isEqualTo(10);
        assertThat(AutoPlacePlanner.intervalTicks(3, 0)).isEqualTo(7);
        assertThat(AutoPlacePlanner.reach(4.5, 0)).isEqualTo(4.5);
        assertThat(AutoPlacePlanner.reach(4.5, 3)).isEqualTo(3);
        assertThat(AutoPlacePlanner.reach(4.5, 8)).isEqualTo(4.5);
    }
}
