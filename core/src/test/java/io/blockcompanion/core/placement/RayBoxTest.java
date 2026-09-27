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

    @Test
    void scrollPushesIntoTheFaceLookedAt() {
        // Looking north at the south face: up (+1) pushes the box north (-z), away from the player.
        RayBox.Hit h = RayBox.intersect(2, 2, 10, 0, 0, -1, box, 64);
        assertThat(h.push(1)).isEqualTo(new io.blockcompanion.core.model.BlockPos(0, 0, -1));
        assertThat(h.push(-2)).isEqualTo(new io.blockcompanion.core.model.BlockPos(0, 0, 2));
        // Looking down at the top face: up pushes it down.
        h = RayBox.intersect(1.5, 20, 1.5, 0.1, -1, 0, box, 64);
        assertThat(h.push(1)).isEqualTo(new io.blockcompanion.core.model.BlockPos(0, -1, 0));
        // Standing inside and looking down: the same way the player faces.
        h = RayBox.intersect(1, 1, 1, 0.2, -0.9, 0.1, box, 64);
        assertThat(h.push(1)).isEqualTo(new io.blockcompanion.core.model.BlockPos(0, -1, 0));
    }

    @Test
    void theNearerBoxIsTheOneLookedAt() {
        RayBox.Hit near = new RayBox.Hit(3, 2, 1, false), far = new RayBox.Hit(8, 2, 1, false);
        RayBox.Hit in = new RayBox.Hit(0, 1, -1, true), in2 = new RayBox.Hit(0, 1, -1, true);
        assertThat(RayBox.before(near, 1000, far, 10)).isTrue();
        assertThat(RayBox.before(far, 10, near, 1000)).isFalse();
        // Seen from outside beats standing in one, however near.
        assertThat(RayBox.before(far, 10, in, 10)).isTrue();
        assertThat(RayBox.before(in, 10, far, 10)).isFalse();
        // Standing in both: the smaller.
        assertThat(RayBox.before(in, 10, in2, 20)).isTrue();
        assertThat(RayBox.before(in, 20, in2, 10)).isFalse();
        // A tie goes to the other one; a miss never wins.
        assertThat(RayBox.before(near, 10, new RayBox.Hit(3, 0, 1, false), 10)).isFalse();
        assertThat(RayBox.before(null, 10, near, 10)).isFalse();
        assertThat(RayBox.before(near, 10, null, 10)).isTrue();
    }
}
