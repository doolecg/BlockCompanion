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
}
