package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AutoBuild's planning: build order, two-block structures as one step, item counts and the need-against-have check. */
class AutoBuildPlanTest {
    static final BlockState PLANKS = BlockState.of("minecraft:oak_planks");
    static final BlockState GLASS = BlockState.of("minecraft:glass");
    static final BlockState STONE = BlockState.of("minecraft:stone");

    static AutoBuildPlan.Cell cell(int x, int y, int z, String state) {
        return new AutoBuildPlan.Cell(x, y, z, BlockState.parse(state));
    }

    @Test
    void layersGoUpFromTheLowestAndStayInAStableOrder() {
        Structure s = new Structure();
        s.set(1, 1, 0, GLASS);
        s.set(0, 1, 0, GLASS);
        s.set(1, 0, 1, PLANKS);
        s.set(0, 0, 1, PLANKS);
        s.set(1, 0, 0, PLANKS);
        s.set(0, 0, 0, PLANKS);
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(new Placement("box.schem", s, new BlockPos(100, 64, 200)));
        assertThat(steps).extracting(st -> new BlockPos(st.main().x(), st.main().y(), st.main().z())).containsExactly(
                new BlockPos(100, 64, 200), new BlockPos(101, 64, 200), new BlockPos(100, 64, 201), new BlockPos(101, 64, 201),
                new BlockPos(100, 65, 200), new BlockPos(101, 65, 200));
        // The same plan every time.
        assertThat(AutoBuildPlan.plan(new Placement("box.schem", s, new BlockPos(100, 64, 200)))).isEqualTo(steps);
    }

    @Test
    void wallThingsGoAfterTheirLayerAndHangingThingsWaitForTheLayerAbove() {
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(List.of(
                cell(0, 0, 0, "minecraft:wall_torch[facing=north]"),
                cell(0, 0, 1, "minecraft:stone"),
                cell(5, 0, 5, "minecraft:lantern[hanging=true]"),
                cell(5, 1, 5, "minecraft:stone"),
                cell(3, 0, 3, "minecraft:stone")));
        assertThat(steps).extracting(st -> st.main().state().path() + "@" + st.main().y()).containsExactly(
                "stone@0", "stone@0", "wall_torch@0", "stone@1", "lantern@0");
    }

    @Test
    void aDoorIsOneStepWithOneItemAndItsUpperHalfIsNotAStepOfItsOwn() {
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(List.of(
                cell(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]")));
        assertThat(steps).hasSize(1);
        AutoBuildPlan.Step door = steps.get(0);
        assertThat(door.main().y()).isZero();
        assertThat(door.partners()).extracting(AutoBuildPlan.Cell::y).containsExactly(1);
        assertThat(door.items()).containsExactly(Map.entry("minecraft:oak_door", 1));
        assertThat(door.kind()).isEqualTo(AutoBuildPlan.Kind.NORMAL);
        assertThat(AutoBuildPlan.required(steps, st -> true)).containsExactly(Map.entry("minecraft:oak_door", 1L));
        assertThat(AutoBuildPlan.count(steps, st -> true)).isEqualTo(1);
    }

    @Test
    void aBedIsOneStepFromItsFootAndATallFlowerFromItsLowerHalf() {
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(List.of(
                cell(0, 0, 0, "minecraft:red_bed[facing=north,occupied=false,part=head]"),
                cell(0, 0, 1, "minecraft:red_bed[facing=north,occupied=false,part=foot]"),
                cell(4, 0, 0, "minecraft:sunflower[half=lower]"),
                cell(4, 1, 0, "minecraft:sunflower[half=upper]")));
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(st -> st.main().state().get("part") + "/" + st.main().state().get("half"))
                .containsExactlyInAnyOrder("foot/null", "null/lower");
        assertThat(AutoBuildPlan.required(steps, st -> true))
                .containsOnly(Map.entry("minecraft:red_bed", 1L), Map.entry("minecraft:sunflower", 1L));
    }

    @Test
    void doorsOfATurnedPlacementStillPair() {
        Structure s = new Structure();
        s.set(0, 0, 0, BlockState.parse("minecraft:spruce_door[facing=east,half=lower,hinge=right,open=true,powered=false]"));
        s.set(0, 1, 0, BlockState.parse("minecraft:spruce_door[facing=east,half=upper,hinge=right,open=true,powered=false]"));
        s.set(1, 0, 0, BlockState.parse("minecraft:white_bed[facing=east,occupied=false,part=foot]"));
        s.set(2, 0, 0, BlockState.parse("minecraft:white_bed[facing=east,occupied=false,part=head]"));
        Placement p = new Placement("turned.schem", s, new BlockPos(0, 64, 0));
        p.setOrientation(1, true);
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(p);
        assertThat(steps).hasSize(2);
        assertThat(steps).allSatisfy(st -> assertThat(st.partners()).hasSize(1));
        assertThat(AutoBuildPlan.required(steps, st -> true))
                .containsOnly(Map.entry("minecraft:spruce_door", 1L), Map.entry("minecraft:white_bed", 1L));
    }

    @Test
    void aLoneUpperHalfAndFluidsCostNothingAndArentPlaced() {
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(List.of(
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
                cell(1, 0, 0, "minecraft:water[level=0]"),
                cell(2, 0, 0, "minecraft:fire[age=0]"),
                cell(3, 0, 0, "minecraft:stone")));
        assertThat(steps).extracting(AutoBuildPlan.Step::kind).containsExactlyInAnyOrder(
                AutoBuildPlan.Kind.FREE, AutoBuildPlan.Kind.FLUID, AutoBuildPlan.Kind.FREE, AutoBuildPlan.Kind.NORMAL);
        assertThat(AutoBuildPlan.required(steps, st -> true)).containsExactly(Map.entry("minecraft:stone", 1L));
        assertThat(AutoBuildPlan.count(steps, st -> true)).isEqualTo(1);
    }

    @Test
    void itemsPerBlockFollowTheSurvivalCosts() {
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(List.of(
                cell(0, 0, 0, "minecraft:oak_slab[type=double,waterlogged=false]"),
                cell(1, 0, 0, "minecraft:wall_torch[facing=east]"),
                cell(2, 0, 0, "minecraft:candle[candles=3,lit=false,waterlogged=false]"),
                cell(3, 0, 0, "minecraft:wheat[age=7]")));
        assertThat(AutoBuildPlan.required(steps, st -> true)).containsOnly(Map.entry("minecraft:oak_slab", 2L), Map.entry("minecraft:torch", 1L),
                Map.entry("minecraft:candle", 3L), Map.entry("minecraft:wheat_seeds", 1L));
    }

    @Test
    void onlyStepsStillToPlaceCount() {
        List<AutoBuildPlan.Step> steps = AutoBuildPlan.plan(List.of(cell(0, 0, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"),
                cell(2, 0, 0, "minecraft:glass")));
        Map<String, Long> need = AutoBuildPlan.required(steps, st -> st.main().x() != 0);
        assertThat(need).containsOnly(Map.entry("minecraft:stone", 1L), Map.entry("minecraft:glass", 1L));
    }

    @Test
    void needsPlacingOnlyWhereTheSpotIsEmpty() {
        AutoBuildPlan.Step door = AutoBuildPlan.plan(List.of(
                cell(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"))).get(0);
        assertThat(AutoBuildPlan.needsPlacing(door, BlockState.AIR)).isTrue();
        assertThat(AutoBuildPlan.needsPlacing(door, BlockState.parse("minecraft:short_grass"))).isTrue();
        // Opened or powered it is still the right door: leave it alone.
        assertThat(AutoBuildPlan.needsPlacing(door,
                BlockState.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=true,powered=true]"))).isFalse();
        // A different block is never replaced.
        assertThat(AutoBuildPlan.needsPlacing(door, STONE)).isFalse();
    }

    @Test
    void shortfallAndItsDescription() {
        Map<String, Long> need = Map.of("minecraft:oak_planks", 76L, "minecraft:glass", 3L, "minecraft:stone", 5L);
        Map<String, Long> have = Map.of("minecraft:oak_planks", 64L, "minecraft:stone", 9L);
        Map<String, Long> shortfall = AutoBuildPlan.shortfall(need, have);
        assertThat(shortfall).containsOnly(Map.entry("minecraft:oak_planks", 12L), Map.entry("minecraft:glass", 3L));
        assertThat(AutoBuildPlan.describeShort(shortfall, 5)).isEqualTo("Short: 12 oak planks, 3 glass");
        assertThat(AutoBuildPlan.describeShort(shortfall, 1)).isEqualTo("Short: 12 oak planks and 1 more");
        assertThat(AutoBuildPlan.shortfall(Map.of("minecraft:stone", 5L), have)).isEmpty();
        assertThat(AutoBuildPlan.describeShort(Map.of(), 3)).isEmpty();
    }
}
