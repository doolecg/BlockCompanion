package io.blockcompanion.core.compare;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkMeshTest {

    static class CollectingSink implements MarkMesh.Sink {
        static class Quad {
            int kind;
            float[] corners;

            Quad(int kind, float[] corners) {
                this.kind = kind;
                this.corners = corners.clone();
            }
        }

        static class Line {
            int kind;
            float ax, ay, az, bx, by, bz;

            Line(int kind, float ax, float ay, float az, float bx, float by, float bz) {
                this.kind = kind;
                this.ax = ax;
                this.ay = ay;
                this.az = az;
                this.bx = bx;
                this.by = by;
                this.bz = bz;
            }

            float length() {
                float dx = bx - ax, dy = by - ay, dz = bz - az;
                return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        List<Quad> quads = new ArrayList<>();
        List<Line> lines = new ArrayList<>();

        @Override
        public void quad(int kind, float[] corners) {
            quads.add(new Quad(kind, corners));
        }

        @Override
        public void line(int kind, float ax, float ay, float az, float bx, float by, float bz) {
            lines.add(new Line(kind, ax, ay, az, bx, by, bz));
        }
    }

    @Test
    void emptyMeshIsEmpty() {
        MarkMesh mesh = new MarkMesh();
        assertThat(mesh.isEmpty()).isTrue();

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);
        assertThat(sink.quads).isEmpty();
        assertThat(sink.lines).isEmpty();
    }

    @Test
    void singleCellKind1At345() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(3, 4, 5, 1);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(6);
        assertThat(sink.quads).allMatch(q -> q.kind == 1);

        assertThat(sink.lines).hasSize(12);
        assertThat(sink.lines).allMatch(l -> l.kind == 1 && Math.abs(l.length() - 1.0f) < 0.001f);
    }

    @Test
    void twoCellsInRowMergedQuads() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(3, 4, 5, 1);
        mesh.set(4, 4, 5, 1);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(6);
        assertThat(sink.quads).allMatch(q -> q.kind == 1);

        assertThat(sink.lines).hasSize(12);
        long lineLengthTwo = sink.lines.stream().filter(l -> Math.abs(l.length() - 2.0f) < 0.001f).count();
        long lineLengthOne = sink.lines.stream().filter(l -> Math.abs(l.length() - 1.0f) < 0.001f).count();
        assertThat(lineLengthTwo).isEqualTo(4);
        assertThat(lineLengthOne).isEqualTo(8);
    }

    @Test
    void solidCube3x3x3At000() {
        MarkMesh mesh = new MarkMesh();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    mesh.set(x, y, z, 1);
                }
            }
        }

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(6);
        assertThat(sink.quads).allMatch(q -> q.kind == 1);

        assertThat(sink.lines).hasSize(12);
        assertThat(sink.lines).allMatch(l -> l.kind == 1 && Math.abs(l.length() - 3.0f) < 0.001f);
    }

    @Test
    void twoCellsDifferentKinds() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(3, 4, 5, 1);
        mesh.set(4, 4, 5, 2);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(12);
        long kind1Quads = sink.quads.stream().filter(q -> q.kind == 1).count();
        long kind2Quads = sink.quads.stream().filter(q -> q.kind == 2).count();
        assertThat(kind1Quads).isEqualTo(6);
        assertThat(kind2Quads).isEqualTo(6);

        assertThat(sink.lines).hasSize(24);
        long kind1Lines = sink.lines.stream().filter(l -> l.kind == 1).count();
        long kind2Lines = sink.lines.stream().filter(l -> l.kind == 2).count();
        assertThat(kind1Lines).isEqualTo(12);
        assertThat(kind2Lines).isEqualTo(12);
    }

    @Test
    void farGridLineBelongsToTheNextSection() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(15, 8, 12, 1);

        CollectingSink notOwned = new CollectingSink();
        mesh.emit(0, false, true, true, notOwned);
        CollectingSink owned = new CollectingSink();
        mesh.emit(0, true, true, true, owned);

        assertThat(notOwned.quads).hasSize(6);
        assertThat(notOwned.lines).hasSize(8).noneMatch(l -> l.ax == 16 && l.bx == 16);
        assertThat(owned.lines).hasSize(12);
    }

    @Test
    void joinsAcrossTheSectionBorder() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(15, 8, 12, 1);
        mesh.set(16, 8, 12, 1);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(5);
        assertThat(sink.lines).hasSize(8).noneMatch(l -> l.ax == 16 && l.bx == 16);
    }

    @Test
    void joinsAcrossTheNearBorder() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(0, 8, 12, 1);
        mesh.set(-1, 8, 12, 1);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(5);
        assertThat(sink.lines).hasSize(8).noneMatch(l -> l.ax == 0 && l.bx == 0);
    }

    @Test
    void lShapeKeepsItsConcaveEdge() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(0, 0, 0, 1);
        mesh.set(1, 0, 0, 1);
        mesh.set(0, 1, 0, 1);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0, true, true, true, sink);

        assertThat(sink.quads).hasSize(10);
        assertThat(sink.lines).hasSize(18);
        assertThat(sink.lines.stream().mapToDouble(CollectingSink.Line::length).sum()).isCloseTo(22, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(sink.lines).anyMatch(l -> l.ax == 1 && l.ay == 1 && l.bx == 1 && l.by == 1);
    }

    @Test
    void inflateJustOutsideUnitCube() {
        MarkMesh mesh = new MarkMesh();
        mesh.set(0, 0, 0, 1);

        CollectingSink sink = new CollectingSink();
        mesh.emit(0.004f, true, true, true, sink);

        assertThat(sink.quads).hasSize(6);
        boolean foundInflated = false;
        for (MarkMeshTest.CollectingSink.Quad q : sink.quads) {
            for (int i = 0; i < 4; i++) {
                float x = q.corners[i * 3];
                float y = q.corners[i * 3 + 1];
                float z = q.corners[i * 3 + 2];
                if (Math.abs(x - 1.004f) < 0.001f || Math.abs(x + 0.004f) < 0.001f
                        || Math.abs(y - 1.004f) < 0.001f || Math.abs(y + 0.004f) < 0.001f
                        || Math.abs(z - 1.004f) < 0.001f || Math.abs(z + 0.004f) < 0.001f) {
                    foundInflated = true;
                }
            }
        }
        assertThat(foundInflated).isTrue();
    }

    @Test
    void illegalKind64Throws() {
        MarkMesh mesh = new MarkMesh();
        assertThatThrownBy(() -> mesh.set(0, 0, 0, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void illegalKindNegativeThrows() {
        MarkMesh mesh = new MarkMesh();
        assertThatThrownBy(() -> mesh.set(0, 0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
