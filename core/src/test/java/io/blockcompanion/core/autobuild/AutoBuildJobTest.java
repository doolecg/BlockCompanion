package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.model.BlockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Running an AutoBuild against a fake world and fake chests. */
class AutoBuildJobTest {
    static final String DIM = "minecraft:overworld";
    static final UUID OWNER = UUID.randomUUID();

    /** A world of loose blocks: anything unset is air. Torches need a block below or a wall behind; sand needs a block below. */
    static final class FakeWorld implements BuildWorld {
        final Map<String, BlockState> blocks = new HashMap<>();
        final List<String> placed = new ArrayList<>();
        final Set<String> unloaded = new HashSet<>();
        boolean creative, online = true, dimension = true;
        int dings;

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        public boolean dimensionExists(String d) {
            return dimension;
        }

        public boolean isLoaded(String d, int x, int y, int z) {
            return !unloaded.contains(key(x, y, z));
        }

        public BlockState get(String d, int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), BlockState.AIR);
        }

        public Check check(String d, int x, int y, int z, BlockState state) {
            if (state.path().equals("sand") || state.path().equals("torch")) {
                return get(d, x, y - 1, z).isAir() ? Check.UNSUPPORTED : Check.OK;
            }
            if (state.path().equals("wall_torch")) {
                // Facing north: hangs on the block to the south.
                return get(d, x, y, z + 1).isAir() ? Check.UNSUPPORTED : Check.OK;
            }
            return Check.OK;
        }

        public boolean place(String d, int x, int y, int z, BlockState state) {
            blocks.put(key(x, y, z), state);
            placed.add(state.path() + "@" + key(x, y, z));
            return true;
        }

        public boolean isCreative(UUID player) {
            return creative;
        }

        public boolean isOnline(UUID player) {
            return online;
        }

        public void ding(UUID player) {
            dings++;
        }
    }

    static final class FakeChests implements AutoBuildJob.Supplies {
        final Map<String, Long> items = new HashMap<>();
        final List<String> taken = new ArrayList<>();

        public long count(String item) {
            return items.getOrDefault(item, 0L);
        }

        public int take(String item, int count) {
            int n = (int) Math.min(count, count(item));
            items.put(item, count(item) - n);
            for (int i = 0; i < n; i++) taken.add(item);
            return n;
        }
    }

    FakeWorld world;
    FakeChests chests;

    @BeforeEach
    void setUp() {
        world = new FakeWorld();
        chests = new FakeChests();
    }

    static AutoBuildPlan.Cell cell(int x, int y, int z, String state) {
        return new AutoBuildPlan.Cell(x, y, z, BlockState.parse(state));
    }

    static AutoBuildJob job(int perSecond, AutoBuildPlan.Cell... cells) {
        return new AutoBuildJob(UUID.randomUUID(), OWNER, "hash", "Castle", DIM, AutoBuildPlan.plan(List.of(cells)), perSecond);
    }

    /** Ticks until it ends (or the limit), returning the last event that wasn't NONE. */
    AutoBuildJob.Event run(AutoBuildJob j, int ticks) {
        AutoBuildJob.Event last = AutoBuildJob.Event.NONE;
        for (int i = 0; i < ticks && !j.state().over() && j.state() != AutoBuildJob.State.PAUSED; i++) {
            AutoBuildJob.Event e = j.tick(world, chests);
            if (e != AutoBuildJob.Event.NONE) last = e;
        }
        return last;
    }

    @Test
    void placesLayerByLayerTakingOneItemPerBlockAndFinishes() {
        chests.items.put("minecraft:stone", 10L);
        AutoBuildJob j = job(20, cell(0, 1, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"), cell(0, 0, 0, "minecraft:stone"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("stone@0,0,0", "stone@1,0,0", "stone@0,1,0");
        assertThat(chests.taken).hasSize(3);
        assertThat(chests.items.get("minecraft:stone")).isEqualTo(7L);
        assertThat(j.placed()).isEqualTo(3);
        assertThat(j.done()).isEqualTo(j.total());
        assertThat(j.summary()).isEqualTo("AutoBuild finished: Castle (3 placed, 0 skipped)");
    }

    @Test
    void speedIsBlocksPerSecond() {
        chests.items.put("minecraft:stone", 100L);
        AutoBuildPlan.Cell[] cells = new AutoBuildPlan.Cell[40];
        for (int i = 0; i < cells.length; i++) cells[i] = cell(i, 0, 0, "minecraft:stone");
        AutoBuildJob j = job(5, cells);
        for (int i = 0; i < 20; i++) j.tick(world, chests);
        assertThat(j.placed()).isEqualTo(5);
    }

    @Test
    void aDoorIsPlacedOnceWithOneItemAndBothHalves() {
        chests.items.put("minecraft:oak_door", 5L);
        AutoBuildJob j = job(20,
                cell(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
                cell(2, 0, 0, "minecraft:red_bed[facing=north,occupied=false,part=foot]"),
                cell(2, 0, -1, "minecraft:red_bed[facing=north,occupied=false,part=head]"));
        chests.items.put("minecraft:red_bed", 1L);
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("oak_door@0,0,0", "oak_door@0,1,0", "red_bed@2,0,0", "red_bed@2,0,-1");
        assertThat(chests.taken).containsExactlyInAnyOrder("minecraft:oak_door", "minecraft:red_bed");
        assertThat(j.total()).isEqualTo(2);
        assertThat(j.placed()).isEqualTo(2);
    }

    @Test
    void anOpenOrPoweredDoorAlreadyThereIsLeftAlone() {
        world.blocks.put("0,0,0", BlockState.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=true,powered=true]"));
        world.blocks.put("0,1,0", BlockState.parse("minecraft:oak_door[facing=north,half=upper,hinge=left,open=true,powered=true]"));
        world.blocks.put("3,0,0", BlockState.parse("minecraft:oak_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]"));
        chests.items.put("minecraft:oak_door", 5L);
        chests.items.put("minecraft:oak_trapdoor", 5L);
        AutoBuildJob j = job(20,
                cell(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
                cell(3, 0, 0, "minecraft:oak_trapdoor[facing=east,half=bottom,open=false,powered=false,waterlogged=false]"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).isEmpty();
        assertThat(chests.taken).isEmpty();
        assertThat(j.alreadyThere()).isEqualTo(2);
        assertThat(j.skipped()).isZero();
    }

    @Test
    void theOtherHalfBlockedMeansNoDoorAtAll() {
        world.blocks.put("0,1,0", BlockState.parse("minecraft:stone"));
        chests.items.put("minecraft:oak_door", 5L);
        AutoBuildJob j = job(20,
                cell(0, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                cell(0, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).isEmpty();
        assertThat(chests.taken).isEmpty();
        assertThat(j.wrongBlocks()).isEqualTo(1);
    }

    @Test
    void wrongBlocksAreNeverBrokenButSkippedAndCounted() {
        world.blocks.put("1,0,0", BlockState.parse("minecraft:dirt"));
        world.blocks.put("2,0,0", BlockState.parse("minecraft:stone"));
        chests.items.put("minecraft:stone", 10L);
        AutoBuildJob j = job(20, cell(0, 0, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"), cell(2, 0, 0, "minecraft:stone"));
        run(j, 100);
        assertThat(world.get(DIM, 1, 0, 0).path()).isEqualTo("dirt");
        assertThat(j.placed()).isEqualTo(1);
        assertThat(j.alreadyThere()).isEqualTo(1);
        assertThat(j.skipped()).isEqualTo(1);
        assertThat(chests.taken).hasSize(1);
        assertThat(j.summary()).isEqualTo("AutoBuild finished: Castle (1 placed, 1 skipped)");
    }

    @Test
    void creativeTakesNothing() {
        world.creative = true;
        AutoBuildJob j = job(20, cell(0, 0, 0, "minecraft:diamond_block"), cell(1, 0, 0, "minecraft:glass"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).hasSize(2);
        assertThat(chests.taken).isEmpty();
    }

    @Test
    void runningOutPausesAndResumesWhereItLeftOff() {
        chests.items.put("minecraft:stone", 1L);
        AutoBuildJob j = job(20, cell(0, 0, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"));
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.PAUSED);
        assertThat(j.state()).isEqualTo(AutoBuildJob.State.PAUSED);
        assertThat(j.message()).startsWith("Out of stone");
        assertThat(j.placed()).isEqualTo(1);
        // Ticking while paused does nothing.
        j.tick(world, chests);
        assertThat(world.placed).hasSize(1);
        chests.items.put("minecraft:stone", 3L);
        j.resume();
        assertThat(run(j, 100)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("stone@0,0,0", "stone@1,0,0");
    }

    @Test
    void unsupportedBlocksWaitForTheEndOfTheirLayerThenGetSkipped() {
        chests.items.put("minecraft:stone", 10L);
        chests.items.put("minecraft:torch", 10L);
        chests.items.put("minecraft:sand", 10L);
        // The torch comes first in the layer's order, but its wall (south of it) comes later in the same layer.
        AutoBuildJob j = job(20,
                cell(0, 0, 0, "minecraft:wall_torch[facing=north]"),
                cell(0, 0, 1, "minecraft:stone"),
                // Sand over a hole: can't stay up, skipped.
                cell(5, 3, 5, "minecraft:sand"));
        assertThat(run(j, 200)).isEqualTo(AutoBuildJob.Event.FINISHED);
        assertThat(world.placed).containsExactly("stone@0,0,1", "wall_torch@0,0,0");
        assertThat(j.skipped()).isEqualTo(1);
        assertThat(chests.items.get("minecraft:sand")).isEqualTo(10L);
    }

    @Test
    void unloadedChunksMakeItWait() {
        chests.items.put("minecraft:stone", 10L);
        world.unloaded.add("1,0,0");
        AutoBuildJob j = job(20, cell(0, 0, 0, "minecraft:stone"), cell(1, 0, 0, "minecraft:stone"));
        for (int i = 0; i < 40; i++) j.tick(world, chests);
        assertThat(j.state()).isEqualTo(AutoBuildJob.State.WAITING);
        assertThat(world.placed).hasSize(1);
        world.unloaded.clear();
        assertThat(run(j, 40)).isEqualTo(AutoBuildJob.Event.FINISHED);
    }

    @Test
    void stopsWhenTheDimensionGoesAndPausesWhenTheOwnerLeaves() {
        AutoBuildJob j = job(20, cell(0, 0, 0, "minecraft:stone"));
        world.online = false;
        assertThat(j.tick(world, chests)).isEqualTo(AutoBuildJob.Event.PAUSED);
        world.online = true;
        j.resume();
        world.dimension = false;
        assertThat(j.tick(world, chests)).isEqualTo(AutoBuildJob.Event.STOPPED);
        assertThat(j.state()).isEqualTo(AutoBuildJob.State.STOPPED);
        assertThat(world.placed).isEmpty();
    }
}
