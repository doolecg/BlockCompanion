package io.blockcompanion.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoxTest {
    @Test
    void overlapCountsSharedCells() {
        Box a = new Box(0, 0, 0, 9, 9, 9);
        assertThat(a.overlap(a)).isEqualTo(1000);
        assertThat(a.overlap(new Box(5, 0, 0, 14, 9, 9))).isEqualTo(500);
        assertThat(a.overlap(new Box(9, 9, 9, 20, 20, 20))).isEqualTo(1);
    }

    @Test
    void boxesThatDontTouchShareNothing() {
        Box a = new Box(0, 0, 0, 9, 9, 9);
        assertThat(a.overlap(new Box(10, 0, 0, 19, 9, 9))).isZero();
        assertThat(a.overlap(new Box(0, -5, 0, 9, -1, 9))).isZero();
    }
}
