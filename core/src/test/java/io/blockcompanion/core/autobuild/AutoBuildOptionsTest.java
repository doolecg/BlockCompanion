package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.blockcompanion.core.autobuild.AutoBuildJobTest.DIM;
import static io.blockcompanion.core.autobuild.AutoBuildJobTest.OWNER;
import static io.blockcompanion.core.autobuild.AutoBuildJobTest.cell;
import static org.assertj.core.api.Assertions.assertThat;

/** AutoBuild's per-build options: replace modes, clearing air, orders, skipping missing items, the radius, one kind of block. */
class AutoBuildOptionsTest {
    AutoBuildJobTest.FakeWorld world;
    Chests chests;

    /** Fake chests that also take drops. */
    static final class Chests implements AutoBuildJob.Supplies {
        final AutoBuildJobTest.FakeChests items = new AutoBuildJobTest.FakeChests();
        final List<LinkedChests.Pos> filled = new ArrayList<>();
        final LinkedChests.Pos chest = new LinkedChests.Pos(DIM, 100, 64, 100);

        public long count(String item) {
            return items.count(item);
        }

        public int take(String item, int count) {
            return items.take(item, count);
        }

        public BuildWorld.Drops drops() {
            return new BuildWorld.Drops() {
                public List<LinkedChests.Pos> chests() {
                    return List.of(chest);
                }

                public void filled(LinkedChests.Pos p) {
                    filled.add(p);
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        world = new AutoBuildJobTest.FakeWorld();
        chests = new Chests();
        chests.items.items.put("minecraft:stone", 100L);
    }

    static AutoBuildOptions opts() {
        return AutoBuildOptions.DEFAULT.withRate(20);
    }

    static AutoBuildJob job(AutoBuildOptions o, List<AutoBuildPlan.Step> steps) {
        return new AutoBuildJob(UUID.randomUUID(), OWNER, "hash", "Castle", DIM, steps, o);
    }

    static AutoBuildJob job(AutoBuildOptions o, AutoBuildPlan.Cell... cells) {
        return job(o, AutoBuildPlan.plan(List.of(cells)));
    }

    AutoBuildJob.Event run(AutoBuildJob j, int ticks) {
        AutoBuildJob.Event last = AutoBuildJob.Event.NONE;
        for (int i = 0; i < ticks && !j.state().over() && j.state() != AutoBuildJob.State.PAUSED; i++) {
            AutoBuildJob.Event e = j.tick(world, chests);
            if (e != AutoBuildJob.Event.NONE) last = e;
        }
        return last;
    }

    void put(int x, int y, int z, String state) {
        world.blocks.put(AutoBuildJobTest.FakeWorld.key(x, y, z), BlockState.parse(state));
    }

    // ---- replace ----------------------------------------------------------------------------------------------------

    @Test
    void keepNeverBreaks() {
        put(1, 0, 0, "minecraft:dirt");
        AutoBuildJob j = job(opts(), cell(0, 0, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"));
        run(j, 100);
        assertThat(world.broken).isEmpty();
        assertThat(j.wrongBlocks()).isEqualTo(1);
    }

    @Test
    void replaceSolidBreaksSolidBlocksButLeavesChestsAndPlants() {
        put(1, 0, 0, "minecraft:dirt");
        put(2, 0, 0, "minecraft:chest");
        put(3, 0, 0, "minecraft:poppy");
        AutoBuildJob j = job(opts().withReplace(AutoBuildOptions.Replace.SOLID),
                cell(1, 0, 0, "minecraft:stone"), cell(2, 0, 0, "minecraft:stone"), cell(3, 0, 0, "minecraft:stone"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.broken).containsExactly("dirt@1,0,0");
        assertThat(world.get(DIM, 1, 0, 0).path()).isEqualTo("stone");
        assertThat(world.get(DIM, 2, 0, 0).path()).isEqualTo("chest");
        // The dirt's drop went to the linked chest; one stone was used.
        assertThat(world.dropped).containsExactly("minecraft:dirt");
        assertThat(chests.filled).containsExactly(chests.chest);
        assertThat(chests.items.items.get("minecraft:stone")).isEqualTo(99L);
        assertThat(j.removed()).isEqualTo(1);
        assertThat(j.wrongBlocks()).isEqualTo(2);
        assertThat(j.summary()).isEqualTo("AutoBuild finished: Castle (1 placed, 2 skipped, 1 removed)");
    }

    @Test
    void replaceAllBreaksAnythingButBedrock() {
        put(1, 0, 0, "minecraft:chest");
        put(2, 0, 0, "minecraft:bedrock");
        AutoBuildJob j = job(opts().withReplace(AutoBuildOptions.Replace.ALL), cell(1, 0, 0, "minecraft:stone"), cell(2, 0, 0, "minecraft:stone"));
        run(j, 100);
        assertThat(world.broken).containsExactly("chest@1,0,0");
        assertThat(world.get(DIM, 2, 0, 0).path()).isEqualTo("bedrock");
    }

    @Test
    void creativeBreaksWithoutDrops() {
        world.creative = true;
        put(0, 0, 0, "minecraft:dirt");
        AutoBuildJob j = job(opts().withReplace(AutoBuildOptions.Replace.SOLID), cell(0, 0, 0, "minecraft:stone"));
        run(j, 100);
        assertThat(world.broken).containsExactly("dirt@0,0,0");
        assertThat(world.dropped).isEmpty();
        assertThat(chests.items.taken).isEmpty();
    }

    @Test
    void aDoorsOtherHalfInTheWayIsBrokenWhenAllowed() {
        put(0, 1, 0, "minecraft:dirt");
        chests.items.items.put("minecraft:oak_door", 1L);
        AutoBuildJob j = job(opts().withReplace(AutoBuildOptions.Replace.SOLID),
                cell(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.get(DIM, 0, 1, 0).get("half")).isEqualTo("upper");
        assertThat(world.broken).containsExactly("dirt@0,1,0");
    }

    // ---- air --------------------------------------------------------------------------------------------------------

    static List<AutoBuildPlan.Step> withAir(AutoBuildPlan.Cell... air) {
        List<AutoBuildPlan.Step> steps = new ArrayList<>(AutoBuildPlan.plan(List.of(cell(0, 0, 0, "minecraft:stone"))));
        steps.addAll(AutoBuildPlan.clearSteps(List.of(air)));
        return steps;
    }

    @Test
    void clearingRemovesBlocksWhereTheSchematicHasAirOnlyWithIgnoreAirOff() {
        put(0, 1, 0, "minecraft:dirt");
        put(1, 0, 0, "minecraft:bedrock");
        List<AutoBuildPlan.Step> steps = withAir(cell(0, 1, 0, "minecraft:air"), cell(1, 0, 0, "minecraft:air"), cell(2, 0, 0, "minecraft:air"));

        // Ignore air on (the default): the air steps aren't part of the build at all.
        AutoBuildJob kept = job(opts().withReplace(AutoBuildOptions.Replace.CLEAR), steps);
        assertThat(kept.total()).isEqualTo(1);
        run(kept, 100);
        assertThat(world.get(DIM, 0, 1, 0).path()).isEqualTo("dirt");

        AutoBuildJob cleared = job(opts().withReplace(AutoBuildOptions.Replace.CLEAR).withIgnoreAir(false), steps);
        assertThat(cleared.total()).isEqualTo(4);
        assertThat(run(cleared, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.get(DIM, 0, 1, 0).isAir()).isTrue();
        assertThat(world.get(DIM, 1, 0, 0).path()).isEqualTo("bedrock");
        assertThat(cleared.removed()).isEqualTo(1);
        assertThat(world.dropped).containsExactly("minecraft:dirt");
    }

    @Test
    void ignoreAirOffAloneDoesNotClear() {
        assertThat(opts().withIgnoreAir(false).clearsAir()).isFalse();
        assertThat(opts().withReplace(AutoBuildOptions.Replace.ALL).withIgnoreAir(false).clearsAir()).isFalse();
        assertThat(opts().withReplace(AutoBuildOptions.Replace.CLEAR).withIgnoreAir(false).clearsAir()).isTrue();
    }

    @Test
    void thePlanFindsTheSchematicsAirInsideItsBounds() {
        Structure s = new Structure();
        s.set(0, 0, 0, BlockState.of("minecraft:stone"));
        s.set(1, 1, 0, BlockState.of("minecraft:stone"));
        s.set(1, 0, 0, BlockState.STRUCTURE_VOID);
        Placement p = new Placement("air.schem", s, new BlockPos(10, 64, 10));
        assertThat(AutoBuildPlan.plan(p, null)).hasSize(2);
        List<AutoBuildPlan.Step> all = AutoBuildPlan.plan(p, c -> true);
        // Bounds 2 x 2 x 1: two stone, one structure void (left alone), one air.
        assertThat(all).hasSize(3);
        assertThat(all.stream().filter(st -> st.kind() == AutoBuildPlan.Kind.CLEAR).map(st -> st.main().x() + "," + st.main().y()))
                .containsExactly("10,65");
        // Clearing comes before placing within a layer.
        assertThat(AutoBuildPlan.plan(p, c -> false)).hasSize(2);
    }

    // ---- order ------------------------------------------------------------------------------------------------------

    @Test
    void topDownBuildsTheHighestLayerFirstAndSupportLast() {
        chests.items.items.put("minecraft:sand", 5L);
        AutoBuildJob j = job(opts().withOrder(AutoBuildOptions.Order.TOP_DOWN),
                cell(0, 0, 0, "minecraft:stone"), cell(0, 2, 0, "minecraft:stone"), cell(5, 1, 0, "minecraft:sand"), cell(5, 0, 0, "minecraft:stone"));
        assertThat(run(j, 200)).isEqualTo(AutoBuildJob.Event.FINISHED);
        // The sand had nothing under it on its turn: it waited for the end, when the stone below was there.
        assertThat(world.placed).containsExactly("stone@0,2,0", "stone@0,0,0", "stone@5,0,0", "sand@5,1,0");
        assertThat(j.skipped()).isZero();
    }

    @Test
    void nearestFirstFollowsThePlayer() {
        world.position = new double[]{10.5, 0, 0.5};
        AutoBuildJob j = job(opts().withOrder(AutoBuildOptions.Order.NEAREST).withRate(1),
                cell(0, 0, 0, "minecraft:stone"), cell(9, 0, 0, "minecraft:stone"), cell(5, 0, 0, "minecraft:stone"), cell(20, 0, 0, "minecraft:stone"));
        for (int i = 0; i < 20; i++) j.tick(world, chests);
        assertThat(world.placed).containsExactly("stone@9,0,0");
        // The player walks to the far end.
        world.position = new double[]{21.5, 0, 0.5};
        for (int i = 0; i < 21; i++) j.tick(world, chests);
        assertThat(world.placed).containsExactly("stone@9,0,0", "stone@20,0,0");
    }

    @Test
    void byBlockPlacesAllOfOneKindThenTheNext() {
        chests.items.items.put("minecraft:glass", 5L);
        AutoBuildJob j = job(opts().withOrder(AutoBuildOptions.Order.BY_BLOCK),
                cell(0, 0, 0, "minecraft:glass"), cell(1, 0, 0, "minecraft:stone"), cell(0, 1, 0, "minecraft:stone"), cell(1, 1, 0, "minecraft:glass"));
        run(j, 100);
        assertThat(world.placed).containsExactly("glass@0,0,0", "glass@1,1,0", "stone@1,0,0", "stone@0,1,0");
    }

    // ---- missing items, radius, one kind ----------------------------------------------------------------------------

    @Test
    void skipMissingCarriesOnInsteadOfPausing() {
        AutoBuildJob j = job(opts().withSkipMissing(true), cell(0, 0, 0, "minecraft:glass"), cell(1, 0, 0, "minecraft:stone"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("stone@1,0,0");
        assertThat(j.missingItems()).isEqualTo(1);
        assertThat(j.skipped()).isEqualTo(1);
    }

    @Test
    void theRadiusBuildsOnlyNearThePlayerAndWaitsForThem() {
        world.position = new double[]{0.5, 0, 0.5};
        AutoBuildJob j = job(opts().withRadius(8), cell(0, 0, 0, "minecraft:stone"), cell(4, 0, 0, "minecraft:stone"), cell(30, 0, 0, "minecraft:stone"));
        for (int i = 0; i < 60; i++) j.tick(world, chests);
        assertThat(world.placed).containsExactly("stone@0,0,0", "stone@4,0,0");
        assertThat(j.state()).isEqualTo(AutoBuildJob.State.WAITING);
        assertThat(j.message()).contains("within 8 blocks");
        // Another dimension: nothing is in range.
        world.position = null;
        for (int i = 0; i < 60; i++) j.tick(world, chests);
        assertThat(world.placed).hasSize(2);
        world.position = new double[]{28.5, 0, 0.5};
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("stone@0,0,0", "stone@4,0,0", "stone@30,0,0");
    }

    @Test
    void onlyOneKindOfBlock() {
        chests.items.items.put("minecraft:glass", 5L);
        AutoBuildJob j = job(opts().withOnlyItem("minecraft:glass"),
                cell(0, 0, 0, "minecraft:glass"), cell(1, 0, 0, "minecraft:stone"), cell(2, 0, 0, "minecraft:glass"));
        assertThat(j.total()).isEqualTo(2);
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("glass@0,0,0", "glass@2,0,0");
    }

    // ---- changing while it runs -------------------------------------------------------------------------------------

    @Test
    void newOptionsWhileRunningGiveSkippedBlocksAnotherGo() {
        put(0, 0, 0, "minecraft:dirt");
        AutoBuildJob j = job(opts().withRate(1), cell(0, 0, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"), cell(5, 0, 0, "minecraft:stone"));
        for (int i = 0; i < 25; i++) j.tick(world, chests);
        assertThat(j.wrongBlocks()).isEqualTo(1);
        assertThat(j.placed()).isEqualTo(1);
        j.setOptions(j.options().withReplace(AutoBuildOptions.Replace.SOLID).withRate(20), List.of());
        assertThat(j.wrongBlocks()).isZero();
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.get(DIM, 0, 0, 0).path()).isEqualTo("stone");
        assertThat(j.placed()).isEqualTo(3);
        assertThat(j.done()).isEqualTo(j.total());
    }

    @Test
    void theSpeedChangesAtOnce() {
        AutoBuildPlan.Cell[] cells = new AutoBuildPlan.Cell[40];
        for (int i = 0; i < cells.length; i++) cells[i] = cell(i, 0, 0, "minecraft:stone");
        AutoBuildJob j = job(opts().withRate(1), cells);
        for (int i = 0; i < 20; i++) j.tick(world, chests);
        assertThat(j.placed()).isEqualTo(1);
        j.setOptions(j.options().withRate(10), null);
        for (int i = 0; i < 20; i++) j.tick(world, chests);
        assertThat(j.placed()).isEqualTo(11);
    }

    // ---- the options themselves -------------------------------------------------------------------------------------

    @Test
    void serversCapTheOptions() {
        AutoBuildOptions o = new AutoBuildOptions(500, AutoBuildOptions.Replace.CLEAR, AutoBuildOptions.Order.TOP_DOWN, false, true, 0, "minecraft:glass");
        AutoBuildOptions c = o.capped(20, AutoBuildOptions.Replace.SOLID, 48);
        assertThat(c.blocksPerSecond()).isEqualTo(20);
        assertThat(c.replace()).isEqualTo(AutoBuildOptions.Replace.SOLID);
        // A server radius limit turns "the whole schematic" into its limit.
        assertThat(c.radius()).isEqualTo(48);
        assertThat(o.withRadius(16).capped(20, AutoBuildOptions.Replace.KEEP, 48).radius()).isEqualTo(16);
        assertThat(o.capped(1000, AutoBuildOptions.Replace.CLEAR, 0)).isEqualTo(o);
        // Out-of-range values are clamped.
        assertThat(new AutoBuildOptions(0, null, null, true, false, -3, null)).isEqualTo(AutoBuildOptions.DEFAULT.withRate(1));
    }

    @Test
    void optionsTravelAsNamedEntries() {
        AutoBuildOptions o = new AutoBuildOptions(40, AutoBuildOptions.Replace.ALL, AutoBuildOptions.Order.BY_BLOCK, false, true, 32, "minecraft:glass");
        assertThat(AutoBuildOptions.fromMaps(o.toMap(), o.toText())).isEqualTo(o);
        assertThat(AutoBuildOptions.fromMaps(java.util.Map.of(), java.util.Map.of())).isEqualTo(AutoBuildOptions.DEFAULT);
        assertThat(AutoBuildOptions.Replace.parse("solid", null)).isEqualTo(AutoBuildOptions.Replace.SOLID);
        assertThat(AutoBuildOptions.Replace.parse("false", null)).isEqualTo(AutoBuildOptions.Replace.KEEP);
        assertThat(AutoBuildOptions.Replace.parse("nonsense", AutoBuildOptions.Replace.ALL)).isEqualTo(AutoBuildOptions.Replace.ALL);
        assertThat(o.describe()).isEqualTo("40 per second, by block, within 32 blocks, replace all, skipping missing items, only glass");
    }
}
