package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.Box;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RayBoxTest {
    private final Box box = new Box(0, 0, 0, 3, 3, 3);

    @Test
    void hitsTheFaceItEntersThrough() {
        // From the south (+z), looking north: enters through the south face, which points to +z.
        RayBox.Hit h = RayBox.intersect(2, 2, 10, 0, 0, -1, box, 64);
        assertThat(h).isNotNull();
        assertThat(h.axis()).isEqualTo(2);
        assertThat(h.sign()).isEqualTo(1);
        assertThat(h.distance()).isEqualTo(6.0);
        // From above, looking down: the top face.
        h = RayBox.intersect(1.5, 20, 1.5, 0.1, -1, 0, box, 64);
        assertThat(h.axis()).isEqualTo(1);
        assertThat(h.sign()).isEqualTo(1);
        // From the west (-x) looking east: the west face points to -x.
        h = RayBox.intersect(-5, 1, 1, 1, 0, 0.2, box, 64);
        assertThat(h.axis()).isZero();
        assertThat(h.sign()).isEqualTo(-1);
    }

    @Test
    void missesAndRange() {
        assertThat(RayBox.intersect(2, 2, 10, 0, 0, 1, box, 64)).isNull();
        assertThat(RayBox.intersect(10, 2, 10, 0, 0, -1, box, 64)).isNull();
        assertThat(RayBox.intersect(2, 2, 100, 0, 0, -1, box, 64)).isNull();
    }

    @Test
    void insideUsesTheLookDirection() {
        RayBox.Hit h = RayBox.intersect(1, 1, 1, 0.2, -0.9, 0.1, box, 64);
        assertThat(h.inside()).isTrue();
        assertThat(h.axis()).isEqualTo(1);
        assertThat(h.sign()).isEqualTo(-1);
    }
}
