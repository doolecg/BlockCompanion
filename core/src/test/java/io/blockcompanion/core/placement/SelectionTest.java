package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionTest {
    @Test
    void cornersInTurnThenStartOver() {
        Selection s = new Selection();
        assertThat(s.box()).isEmpty();
        assertThat(s.mark(new BlockPos(5, 70, -3), "minecraft:overworld")).isEqualTo(1);
        assertThat(s.box()).contains(new Box(5, 70, -3, 5, 70, -3));
        assertThat(s.isComplete()).isFalse();
        assertThat(s.mark(new BlockPos(-2, 64, 4), "minecraft:overworld")).isEqualTo(2);
        assertThat(s.isComplete()).isTrue();
        assertThat(s.box()).contains(new Box(-2, 64, -3, 5, 70, 4));
        // A third corner starts a new selection.
        assertThat(s.mark(new BlockPos(0, 0, 0), "minecraft:overworld")).isEqualTo(1);
        assertThat(s.second()).isNull();
        // A corner in another dimension starts over too.
        assertThat(s.mark(new BlockPos(1, 1, 1), "minecraft:the_nether")).isEqualTo(1);
        assertThat(s.dimension()).isEqualTo("minecraft:the_nether");
        s.clear();
        assertThat(s.isEmpty()).isTrue();
    }

    @Test
    void movesBothCornersTogether() {
        Selection s = new Selection();
        assertThat(s.move(new BlockPos(1, 0, 0))).isFalse();
        s.set(1, new BlockPos(0, 64, 0), "minecraft:overworld");
        s.set(2, new BlockPos(4, 66, 2), "minecraft:overworld");
        assertThat(s.move(new BlockPos(0, 0, -3))).isTrue();
        assertThat(s.first()).isEqualTo(new BlockPos(0, 64, -3));
        assertThat(s.second()).isEqualTo(new BlockPos(4, 66, -1));
        assertThat(s.box()).contains(new Box(0, 64, -3, 4, 66, -1));
        assertThat(s.dimension()).isEqualTo("minecraft:overworld");

        // With one corner, that block moves.
        Selection one = new Selection();
        one.mark(new BlockPos(5, 5, 5), "minecraft:overworld");
        one.move(new BlockPos(0, 2, 0));
        assertThat(one.first()).isEqualTo(new BlockPos(5, 7, 5));
        assertThat(one.second()).isNull();
    }

    @Test
    void undoStateIsTheLowestCorner() {
        Selection s = new Selection();
        s.set(1, new BlockPos(4, 70, 2), "minecraft:overworld");
        s.set(2, new BlockPos(0, 64, 9), "minecraft:overworld");
        PlacementHistory.State at = s.state();
        assertThat(at.origin()).isEqualTo(new BlockPos(0, 64, 2));

        s.move(new BlockPos(3, 0, 0));
        assertThat(s.state().diff(at)).isEqualTo(PlacementHistory.Kind.POSITION);
        s.apply(at);
        assertThat(s.first()).isEqualTo(new BlockPos(4, 70, 2));
        assertThat(s.second()).isEqualTo(new BlockPos(0, 64, 9));
    }

    @Test
    void movesUndoUntilACornerIsMarked() {
        Selection s = new Selection();
        s.set(1, new BlockPos(0, 64, 0), "minecraft:overworld");
        s.set(2, new BlockPos(2, 64, 2), "minecraft:overworld");
        PlacementHistory.State before = s.state();
        s.move(new BlockPos(1, 0, 0));
        assertThat(s.history().record(before, s.state(), 0)).isTrue();

        PlacementHistory.Step step = s.history().undo(s.state());
        s.apply(step.target());
        assertThat(s.box()).contains(new Box(0, 64, 0, 2, 64, 2));
        assertThat(s.history().canRedo()).isTrue();

        // Marking a corner (or clearing) starts over: the old move can't shift the new selection.
        s.set(2, new BlockPos(5, 64, 5), "minecraft:overworld");
        assertThat(s.history().canRedo()).isFalse();
        assertThat(s.history().canUndo()).isFalse();
    }
}
