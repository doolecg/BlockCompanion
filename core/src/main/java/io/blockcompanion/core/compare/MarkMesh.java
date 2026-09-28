package io.blockcompanion.core.compare;

/**
 * Merges the marks over wrong and in-the-way blocks in one 16x16x16 section. Touching cells of the same kind make one
 * shape: its shell leaves out the faces between them and joins flat runs into large quads, and its outline follows only
 * the shape's own edges, joined into long lines, instead of a box around every block.
 *
 * <p>Cells are local to the section, 0 to 15, with a one-cell border (-1 and 16) holding the neighbouring sections'
 * cells, so shapes join across section borders. An outline edge on the far border (grid line 16) belongs to the next
 * section, unless the caller says there is none there.
 */
public final class MarkMesh {
    public static final int SIZE = 16;
    private static final int W = SIZE + 2;

    /** Receives the merged shapes: quads as 4 corners of x, y, z (the array is reused), lines as their 2 ends. */
    public interface Sink {
        void quad(int kind, float[] corners);

        void line(int kind, float ax, float ay, float az, float bx, float by, float bz);
    }

    private final byte[] kinds = new byte[W * W * W];
    /** Bit n set when a cell inside the section has kind n. */
    private long present;

    private static int index(int x, int y, int z) {
        return ((y + 1) * W + (z + 1)) * W + (x + 1);
    }

    /** Sets a cell's mark: 0 for none, 1 to 63 for a kind. x, y and z run from -1 to 16. */
    public void set(int x, int y, int z, int kind) {
        if (kind < 0 || kind > 63) throw new IllegalArgumentException("kind " + kind);
        kinds[index(x, y, z)] = (byte) kind;
        if (kind != 0 && x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE) present |= 1L << kind;
    }

    public int get(int x, int y, int z) {
        return kinds[index(x, y, z)];
    }

    /** Whether no cell inside the section has a mark. */
    public boolean isEmpty() {
        return present == 0;
    }

    /** The cell at {@code s} along {@code axis}, {@code u} and {@code v} along the next two axes (x, y, z cyclic). */
    private int at(int axis, int s, int u, int v) {
        return switch (axis) {
            case 0 -> get(s, u, v);
            case 1 -> get(v, s, u);
            default -> get(u, v, s);
        };
    }

    private static void put(float[] out, int i, int axis, float s, float u, float v) {
        switch (axis) {
            case 0 -> {
                out[i] = s;
                out[i + 1] = u;
                out[i + 2] = v;
            }
            case 1 -> {
                out[i] = v;
                out[i + 1] = s;
                out[i + 2] = u;
            }
            default -> {
                out[i] = u;
                out[i + 1] = v;
                out[i + 2] = s;
            }
        }
    }

    /**
     * Hands the merged shells and outlines to {@code sink}, pushed {@code inflate} outwards so they sit just off the
     * blocks (shell quads only along their normal). {@code ownFarX}, {@code ownFarY} and {@code ownFarZ} say whether outline edges on grid line 16 of that
     * axis are this section's (no section follows there).
     */
    public void emit(float inflate, boolean ownFarX, boolean ownFarY, boolean ownFarZ, Sink sink) {
        if (present == 0) return;
        faces(inflate, sink);
        boolean[] ownFar = {ownFarX, ownFarY, ownFarZ};
        for (int kind = 1; kind < 64; kind++) {
            if ((present & (1L << kind)) == 0) continue;
            for (int axis = 0; axis < 3; axis++) edges(kind, axis, ownFar[(axis + 1) % 3], ownFar[(axis + 2) % 3], inflate, sink);
        }
    }

    /** The shells: each face with no same-kind cell beyond it, merged into rectangles per slice. */
    private void faces(float inflate, Sink sink) {
        int[] mask = new int[SIZE * SIZE];
        float[] q = new float[12];
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int s = 0; s < SIZE; s++) {
                    boolean anyFace = false;
                    for (int u = 0; u < SIZE; u++) {
                        for (int v = 0; v < SIZE; v++) {
                            int k = at(axis, s, u, v);
                            int m = k != 0 && at(axis, s + dir, u, v) != k ? k : 0;
                            mask[u * SIZE + v] = m;
                            anyFace |= m != 0;
                        }
                    }
                    if (!anyFace) continue;
                    float plane = s + (dir > 0 ? 1 : 0) + dir * inflate;
                    for (int u = 0; u < SIZE; u++) {
                        for (int v = 0; v < SIZE; ) {
                            int k = mask[u * SIZE + v];
                            if (k == 0) {
                                v++;
                                continue;
                            }
                            int v1 = v + 1;
                            while (v1 < SIZE && mask[u * SIZE + v1] == k) v1++;
                            int u1 = u + 1;
                            grow:
                            while (u1 < SIZE) {
                                for (int i = v; i < v1; i++) if (mask[u1 * SIZE + i] != k) break grow;
                                u1++;
                            }
                            for (int a = u; a < u1; a++) for (int b = v; b < v1; b++) mask[a * SIZE + b] = 0;
                            // Pushed out along the normal only: quads side by side in a plane must not overlap and
                            // blend twice. Counter-clockwise seen from outside the shape.
                            float u0f = u, u1f = u1, v0f = v, v1f = v1;
                            put(q, 0, axis, plane, u0f, v0f);
                            if (dir > 0) {
                                put(q, 3, axis, plane, u1f, v0f);
                                put(q, 6, axis, plane, u1f, v1f);
                                put(q, 9, axis, plane, u0f, v1f);
                            } else {
                                put(q, 3, axis, plane, u0f, v1f);
                                put(q, 6, axis, plane, u1f, v1f);
                                put(q, 9, axis, plane, u1f, v0f);
                            }
                            sink.quad(k, q);
                            v = v1;
                        }
                    }
                }
            }
        }
    }

    /**
     * The outline edges running along {@code axis}: an edge is drawn where the four cells around it don't make a flat
     * surface or a solid (one or three of them, or two diagonal ones), joined along the axis while that stays so.
     */
    private void edges(int kind, int axis, boolean ownFarU, boolean ownFarV, float inflate, Sink sink) {
        int uMax = ownFarU ? SIZE : SIZE - 1, vMax = ownFarV ? SIZE : SIZE - 1;
        float[] p = new float[6];
        for (int u = 0; u <= uMax; u++) {
            for (int v = 0; v <= vMax; v++) {
                int runStart = 0, run = 0;
                for (int t = 0; t <= SIZE; t++) {
                    int pattern = t < SIZE ? pattern(kind, axis, t, u, v) : 0;
                    if (pattern == run) continue;
                    if (run != 0) {
                        // Off the blocks, towards the empty side of the corner.
                        int a = run & 1, b = run >> 1 & 1, c = run >> 2 & 1, d = run >> 3 & 1;
                        float du = -Integer.signum(b + d - a - c) * inflate, dv = -Integer.signum(c + d - a - b) * inflate;
                        put(p, 0, axis, runStart - inflate, u + du, v + dv);
                        put(p, 3, axis, t + inflate, u + du, v + dv);
                        sink.line(kind, p[0], p[1], p[2], p[3], p[4], p[5]);
                    }
                    runStart = t;
                    run = pattern;
                }
            }
        }
    }

    /**
     * The cells of {@code kind} around the edge at grid ({@code u}, {@code v}) in slab {@code t}, as bits (1: u-1 v-1,
     * 2: u v-1, 4: u-1 v, 8: u v), or 0 when no edge shows there.
     */
    private int pattern(int kind, int axis, int t, int u, int v) {
        int bits = (at(axis, t, u - 1, v - 1) == kind ? 1 : 0) | (at(axis, t, u, v - 1) == kind ? 2 : 0)
                | (at(axis, t, u - 1, v) == kind ? 4 : 0) | (at(axis, t, u, v) == kind ? 8 : 0);
        int n = Integer.bitCount(bits);
        return n == 1 || n == 3 || bits == 0b1001 || bits == 0b0110 ? bits : 0;
    }
}
