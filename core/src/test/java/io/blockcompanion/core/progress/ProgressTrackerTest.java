package io.blockcompanion.core.progress;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Placement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressTrackerTest {
    static final BlockState STONE = BlockState.of("minecraft:stone");
    static final BlockState PLANKS = BlockState.of("minecraft:oak_planks");
    static final BlockState DIRT = BlockState.of("minecraft:dirt");
    static final BlockState AIR = BlockState.AIR;
    static final BlockState STAIRS_N = BlockState.parse("oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
    static final BlockState DOUBLE_SLAB = BlockState.parse("stone_slab[type=double,waterlogged=false]");
    static final BlockState DOOR_LOWER = BlockState.parse("oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
    static final BlockState DOOR_UPPER = BlockState.parse("oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");

    /** A fake world: whatever was put, air elsewhere. */
    static final class World implements ProgressTracker.CellSource {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        @Override
        public BlockState get(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPos(x, y, z), AIR);
        }

        void put(int x, int y, int z, BlockState s) {
            blocks.put(new BlockPos(x, y, z), s);
        }
    }

    /**
     * 4 wide (x), 3 tall, 1 deep: level 0 is stone stone planks stairs, level 1 is a double slab and a door (lower), level 2
     * the door's upper half. 8 blocks.
     */
    static Structure sample() {
        Structure s = new Structure();
        s.set(0, 0, 0, STONE);
        s.set(1, 0, 0, STONE);
        s.set(2, 0, 0, PLANKS);
        s.set(3, 0, 0, STAIRS_N);
        s.set(0, 1, 0, DOUBLE_SLAB);
        s.set(1, 1, 0, DOOR_LOWER);
        s.set(1, 2, 0, DOOR_UPPER);
        s.set(3, 2, 0, STONE);
        return s;
    }

    static Placement placed() {
        return new Placement("sample.schem", sample(), new BlockPos(10, 64, 20));
    }

    static void scanAll(ProgressTracker t, World w) {
        for (long k : t.sectionKeys()) {
            BlockPos s = BlockPos.unpack(k);
            t.scanSection(s.x(), s.y(), s.z(), w);
        }
    }

    @Test
    void startsWithEverythingMissingAndCountsItemsTheResourceListWay() {
        ProgressTracker t = new ProgressTracker(placed());
        ProgressTracker.Totals all = t.totals();
        assertThat(all.total()).isEqualTo(8);
        assertThat(all.correct()).isZero();
        assertThat(all.missing()).isEqualTo(8);
        Map<String, ProgressTracker.ItemProgress> items = t.items();
        assertThat(items.get("minecraft:stone").needed()).isEqualTo(3);
        // A double slab is two slabs, a door one item for both halves.
        assertThat(items.get("minecraft:stone_slab").needed()).isEqualTo(2);
        assertThat(items.get("minecraft:oak_door").needed()).isEqualTo(1);
        assertThat(items.values()).allMatch(p -> p.placed() == 0);
        assertThat(t.status(10, 64, 20)).isEqualTo(ProgressTracker.Status.UNKNOWN);
        assertThat(t.unseenSections()).isEqualTo(1);
    }

    @Test
    void singleBlockChangesUpdateTotalsAndItemsIncrementally() {
        ProgressTracker t = new ProgressTracker(placed());
        World w = new World();
        scanAll(t, w);
        assertThat(t.status(10, 64, 20)).isEqualTo(ProgressTracker.Status.MISSING);

        ProgressTracker.Change c = t.set(10, 64, 20, STONE);
        assertThat(c.before()).isEqualTo(ProgressTracker.Status.MISSING);
        assertThat(c.after()).isEqualTo(ProgressTracker.Status.CORRECT);
        assertThat(t.totals().correct()).isEqualTo(1);
        assertThat(t.item("minecraft:stone").placed()).isEqualTo(1);

        // The same state again is no change.
        assertThat(t.set(10, 64, 20, STONE)).isNull();

        // A wrong block, then broken again.
        c = t.set(11, 64, 20, DIRT);
        assertThat(c.after()).isEqualTo(ProgressTracker.Status.WRONG);
        assertThat(t.totals().wrong()).isEqualTo(1);
        assertThat(t.totals().missing()).isEqualTo(6);
        c = t.set(11, 64, 20, AIR);
        assertThat(c.after()).isEqualTo(ProgressTracker.Status.MISSING);
        assertThat(t.totals().wrong()).isZero();

        // Placed then broken: the item count goes back down.
        t.set(10, 65, 20, DOUBLE_SLAB);
        assertThat(t.item("minecraft:stone_slab").placed()).isEqualTo(2);
        t.set(10, 65, 20, AIR);
        assertThat(t.item("minecraft:stone_slab").placed()).isZero();

        // Only the lower door half carries the item.
        t.set(11, 65, 20, DOOR_LOWER);
        t.set(11, 66, 20, DOOR_UPPER);
        assertThat(t.item("minecraft:oak_door").placed()).isEqualTo(1);

        // Stairs with another shape still count (the same leniency as the renderer); facing the wrong way does not.
        assertThat(t.set(13, 64, 20, STAIRS_N.with("shape", "outer_left")).after()).isEqualTo(ProgressTracker.Status.CORRECT);
        assertThat(t.set(13, 64, 20, STAIRS_N.with("facing", "east")).after()).isEqualTo(ProgressTracker.Status.WRONG);

        // Outside the box nothing happens.
        assertThat(t.set(9, 64, 20, STONE)).isNull();
    }

    @Test
    void extraBlocksAreCountedApartFromTheSchematicTotal() {
        ProgressTracker t = new ProgressTracker(placed());
        ProgressTracker.Change c = t.set(12, 65, 20, DIRT);
        assertThat(c.after()).isEqualTo(ProgressTracker.Status.EXTRA);
        assertThat(t.totals().extra()).isEqualTo(1);
        assertThat(t.totals().total()).isEqualTo(8);
        // Grass and similar clutter isn't in the way.
        assertThat(t.set(12, 65, 20, BlockState.of("minecraft:short_grass")).after()).isEqualTo(ProgressTracker.Status.NONE);
        assertThat(t.totals().extra()).isZero();
    }

    @Test
    void levelsAndCompletionAreReported() {
        ProgressTracker t = new ProgressTracker(placed());
        scanAll(t, new World());
        assertThat(t.set(10, 64, 20, STONE).levelCompleted()).isFalse();
        t.set(11, 64, 20, STONE);
        t.set(12, 64, 20, PLANKS);
        ProgressTracker.Change last = t.set(13, 64, 20, STAIRS_N);
        assertThat(last.level()).isZero();
        assertThat(last.levelCompleted()).isTrue();
        assertThat(last.allCompleted()).isFalse();
        assertThat(t.levelComplete(0)).isTrue();
        assertThat(t.totals(0, 0).complete()).isTrue();
        assertThat(t.totals(1, 2).correct()).isZero();
        assertThat(t.items(1, 1)).containsOnlyKeys("minecraft:stone_slab", "minecraft:oak_door");

        t.set(10, 65, 20, DOUBLE_SLAB);
        t.set(11, 65, 20, DOOR_LOWER);
        t.set(11, 66, 20, DOOR_UPPER);
        ProgressTracker.Change fin = t.set(13, 66, 20, STONE);
        assertThat(fin.levelCompleted()).isTrue();
        assertThat(fin.allCompleted()).isTrue();
        assertThat(t.totals().fraction()).isEqualTo(1.0);
    }

    @Test
    void unloadedChunksKeepTheirLastKnownState() {
        ProgressTracker t = new ProgressTracker(placed());
        World w = new World();
        w.put(10, 64, 20, STONE);
        w.put(11, 64, 20, STONE);
        scanAll(t, w);
        assertThat(t.totals().correct()).isEqualTo(2);
        assertThat(t.lastSeenSections()).isZero();

        // Walk away: the chunk unloads, the count stays and is marked last seen.
        t.markUnloaded(10 >> 4, 20 >> 4);
        assertThat(t.totals().correct()).isEqualTo(2);
        assertThat(t.lastSeenSections()).isEqualTo(1);
        assertThat(t.columnLoaded(0, 1)).isFalse();

        // Meanwhile someone broke a block; the rescan on return picks it up.
        w.put(11, 64, 20, AIR);
        List<ProgressTracker.Change> changes = t.sectionKeys().stream()
                .flatMap(k -> {
                    BlockPos s = BlockPos.unpack(k);
                    return t.scanSection(s.x(), s.y(), s.z(), w).stream();
                }).toList();
        assertThat(changes).hasSize(1);
        assertThat(t.totals().correct()).isEqualTo(1);
        assertThat(t.lastSeenSections()).isZero();
    }

    @Test
    void rotatedPlacementsCompareTurnedStates() {
        Placement p = placed();
        p.setOrientation(1, false);
        ProgressTracker t = new ProgressTracker(p);
        // Find where the stairs landed and what they should look like now.
        BlockPos w = p.toWorld(3, 0, 0);
        BlockState want = p.stateAt(w.x(), w.y(), w.z());
        assertThat(want.get("facing")).isEqualTo("east");
        assertThat(t.set(w.x(), w.y(), w.z(), STAIRS_N).after()).isEqualTo(ProgressTracker.Status.WRONG);
        assertThat(t.set(w.x(), w.y(), w.z(), want).after()).isEqualTo(ProgressTracker.Status.CORRECT);
        assertThat(t.matches(p)).isTrue();
        p.move(1, 0, 0);
        assertThat(t.matches(p)).isFalse();
    }

    @Test
    void lastKnownStateSurvivesSavingAndRejectsAnotherPose() throws IOException {
        ProgressTracker t = new ProgressTracker(placed());
        World w = new World();
        w.put(10, 64, 20, STONE);
        w.put(11, 64, 20, DIRT);
        scanAll(t, w);
        t.stats().placed = 5;
        t.stats().buildMillis = 1234;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        t.write(bytes, "abc");

        ProgressTracker back = new ProgressTracker(placed());
        assertThat(back.read(new ByteArrayInputStream(bytes.toByteArray()), "abc")).isTrue();
        assertThat(back.totals()).isEqualTo(t.totals());
        assertThat(back.item("minecraft:stone").placed()).isEqualTo(1);
        assertThat(back.stats().placed).isEqualTo(5);
        assertThat(back.stats().buildMillis).isEqualTo(1234);
        // Restored sections are "last seen" until scanned again.
        assertThat(back.lastSeenSections()).isEqualTo(1);

        ProgressTracker otherId = new ProgressTracker(placed());
        assertThat(otherId.read(new ByteArrayInputStream(bytes.toByteArray()), "xyz")).isFalse();
        Placement moved = placed();
        moved.move(0, 1, 0);
        ProgressTracker otherPose = new ProgressTracker(moved);
        assertThat(otherPose.read(new ByteArrayInputStream(bytes.toByteArray()), "abc")).isFalse();
        assertThat(otherPose.totals().correct()).isZero();
    }
}
