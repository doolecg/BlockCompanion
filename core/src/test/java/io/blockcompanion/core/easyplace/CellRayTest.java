package io.blockcompanion.core.easyplace;

import io.blockcompanion.core.easyplace.EasyPlacePlanner.Dir;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CellRayTest {
    @Test
    void hitsTheFirstAcceptedCellAndTheFaceEntered() {
        // Looking east from (0.5, 64.5, 0.5); ghost cells at x = 3 and x = 5.
        CellRay.Hit h = CellRay.cast(0.5, 64.5, 0.5, 1, 0, 0, 10, (x, y, z) -> y == 64 && z == 0 && (x == 3 || x == 5));
        assertThat(h).isNotNull();
        assertThat(h.x()).isEqualTo(3);
        assertThat(h.face()).isEqualTo(Dir.WEST);
        assertThat(h.distance()).isCloseTo(2.5, within(1e-9));
        assertThat(h.hx()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void looksDownOntoTheTopFace() {
        CellRay.Hit h = CellRay.cast(0.5, 70.2, 0.5, 0, -1, 0, 10, (x, y, z) -> y == 64);
        assertThat(h.y()).isEqualTo(64);
        assertThat(h.face()).isEqualTo(Dir.UP);
        assertThat(h.hy()).isCloseTo(65.0, within(1e-9));
    }

    @Test
    void missesBeyondReachAndWithoutDirection() {
        assertThat(CellRay.cast(0.5, 64.5, 0.5, 1, 0, 0, 2, (x, y, z) -> x == 3)).isNull();
        assertThat(CellRay.cast(0.5, 64.5, 0.5, 0, 0, 0, 5, (x, y, z) -> true)).isNull();
    }

    @Test
    void diagonalRaysVisitEveryCellTheyCross() {
        // A ray going +x+z crosses cells in order; the accepted cell must be found, not skipped.
        CellRay.Hit h = CellRay.cast(0.2, 0.5, 0.1, 1, 0, 1, 10, (x, y, z) -> x == 2 && z == 2);
        assertThat(h).isNotNull();
        assertThat(h.x()).isEqualTo(2);
        assertThat(h.z()).isEqualTo(2);
        assertThat(h.face()).isIn(Dir.WEST, Dir.NORTH);
    }

    @Test
    void startingInsideAGhostCountsAtOnce() {
        CellRay.Hit h = CellRay.cast(3.5, 64.5, 0.5, 0, 0, -1, 10, (x, y, z) -> x == 3 && y == 64 && z == 0);
        assertThat(h.distance()).isZero();
        assertThat(h.face()).isEqualTo(Dir.SOUTH);
    }
}
