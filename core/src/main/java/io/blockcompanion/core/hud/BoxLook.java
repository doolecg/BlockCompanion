package io.blockcompanion.core.hud;

/**
 * How a box is drawn in the world (the save selection and the placement boxes): faint tinted faces on all six sides
 * that breathe slowly, the face being looked at a little brighter, a crisp outline, and lighter corner brackets. The
 * whole box fades in and out quickly when it is shown or hidden (the selection tool taken out or put away). Plain
 * numbers only; each Minecraft version draws with them.
 */
public final class BoxLook {
    /** Outline colour of a placement box that is neither selected, looked at nor locked. */
    public static final int OTHER_RGB = 0xC8C8C8;

    /** Face opacity (0 to 1) at the middle of the pulse. */
    public static final double FACE_ALPHA = 0.075;
    /** Extra face opacity on the face being looked at. */
    public static final double FACE_LOOKED_EXTRA = 0.07;
    /** How far the pulse moves the face opacity, relative to it (0.3 = plus or minus 30%). */
    public static final double PULSE_DEPTH = 0.3;
    /** One slow breath of the faces. */
    public static final long PULSE_MS = 3600;
    /** Outline opacity of a box that matters (the selection, the selected, looked-at or locked placement). */
    public static final double EDGE_ALPHA = 0.9;
    /** Outline opacity of the other placement boxes. */
    public static final double EDGE_ALPHA_OTHER = 0.5;
    /** How much the corner brackets are lightened toward white. */
    public static final double ACCENT_LIGHTEN = 0.45;
    /** Show and hide take this long. */
    public static final long FADE_MS = 180;
    /** How far the box is pushed out from the blocks, so its faces never fight with them. */
    public static final float INFLATE = 0.012f;

    private BoxLook() {
    }

    /** The fade after {@code dtMs} more milliseconds heading to shown (1) or hidden (0). */
    public static double stepFade(double fade, boolean shown, long dtMs) {
        double step = Math.max(0, dtMs) / (double) FADE_MS;
        double v = shown ? fade + step : fade - step;
        return Math.max(0, Math.min(1, v));
    }

    /** Eased fade: quick at the start, soft at the end. */
    public static double ease(double fade) {
        double f = Math.max(0, Math.min(1, fade));
        return 1 - (1 - f) * (1 - f);
    }

    /** 0 to 1 and back over {@link #PULSE_MS}. */
    public static double pulse(long timeMs) {
        double t = Math.floorMod(timeMs, PULSE_MS) / (double) PULSE_MS;
        return 0.5 + 0.5 * Math.sin(t * Math.PI * 2);
    }

    /** A face's colour: {@code rgb} with the breathing face opacity, brighter when looked at, times the fade. */
    public static int faceArgb(int rgb, boolean lookedAt, long timeMs, double fade) {
        double a = FACE_ALPHA * (1 + PULSE_DEPTH * (pulse(timeMs) * 2 - 1));
        if (lookedAt) a += FACE_LOOKED_EXTRA;
        return argb(rgb, a * ease(fade));
    }

    /** The outline's colour. */
    public static int edgeArgb(int rgb, double alpha, double fade) {
        return argb(rgb, alpha * ease(fade));
    }

    /** The corner brackets' colour: the outline colour lightened. */
    public static int accentArgb(int rgb, double alpha, double fade) {
        return argb(Colors.mix(rgb, 0xFFFFFF, ACCENT_LIGHTEN), Math.min(1, alpha + 0.1) * ease(fade));
    }

    /** How long a corner bracket arm is along an edge of {@code size} blocks. */
    public static float bracket(float size) {
        return Math.min(0.75f, Math.max(0.2f, size * 0.22f));
    }

    /**
     * Index (0 to 5) of the face a {@code RayBox} hit points at: -x, +x, -y, +y, -z, +z. Entering from outside that is
     * the face the ray crosses; standing inside it is the face the player faces.
     */
    public static int face(int axis, int sign) {
        return axis * 2 + (sign > 0 ? 1 : 0);
    }

    /**
     * The six faces of a box from (x0, y0, z0) to (x1, y1, z1) as quads, in {@link #face} order: 6 faces times 4
     * corners times x, y, z.
     */
    public static float[] faceQuads(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new float[]{
                x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0,
                x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1,
                x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1,
                x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0,
                x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
    }

    /** The 12 edges of the box as line segments: 12 times two ends times x, y, z. */
    public static float[] edges(float x0, float y0, float z0, float x1, float y1, float z1) {
        float[][] c = corners(x0, y0, z0, x1, y1, z1);
        int[][] e = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        float[] out = new float[12 * 6];
        int i = 0;
        for (int[] p : e) {
            for (float v : c[p[0]]) out[i++] = v;
            for (float v : c[p[1]]) out[i++] = v;
        }
        return out;
    }

    /** Corner brackets: from each of the 8 corners, a short arm along each of its 3 edges. 24 segments, 6 floats each. */
    public static float[] brackets(float x0, float y0, float z0, float x1, float y1, float z1) {
        float lx = bracket(x1 - x0), ly = bracket(y1 - y0), lz = bracket(z1 - z0);
        float[] out = new float[24 * 6];
        int i = 0;
        for (int corner = 0; corner < 8; corner++) {
            boolean hx = (corner & 1) != 0, hy = (corner & 2) != 0, hz = (corner & 4) != 0;
            float x = hx ? x1 : x0, y = hy ? y1 : y0, z = hz ? z1 : z0;
            float dx = hx ? -lx : lx, dy = hy ? -ly : ly, dz = hz ? -lz : lz;
            float[][] arms = {{x + dx, y, z}, {x, y + dy, z}, {x, y, z + dz}};
            for (float[] a : arms) {
                out[i++] = x;
                out[i++] = y;
                out[i++] = z;
                out[i++] = a[0];
                out[i++] = a[1];
                out[i++] = a[2];
            }
        }
        return out;
    }

    private static float[][] corners(float x0, float y0, float z0, float x1, float y1, float z1) {
        return new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
    }

    private static int argb(int rgb, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
