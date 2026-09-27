package io.blockcompanion.core.hud;

/** Colours shared by the game's bars and lists. */
public final class Colors {
    /** Red, orange, yellow, green: along a bar from empty to full. */
    private static final int[] STOPS = {0xD8413A, 0xE8872F, 0xE9CF3F, 0x5DBE4A};

    private Colors() {
    }

    /**
     * The colour at {@code t} (0 to 1) along red, orange, yellow, green, opaque. A bar filled to {@code f} draws each
     * column at its own position, so a full bar shows the whole gradient and a short one only red and orange.
     */
    public static int gradient(double t) {
        double v = Math.max(0, Math.min(1, t)) * (STOPS.length - 1);
        int i = Math.min(STOPS.length - 2, (int) Math.floor(v));
        double f = v - i;
        int a = STOPS[i], b = STOPS[i + 1];
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = (int) Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** {@code argb} with its colour channels multiplied by {@code k} (darker below 1). */
    public static int shade(int argb, double k) {
        int r = (int) Math.min(255, ((argb >> 16) & 0xFF) * k);
        int g = (int) Math.min(255, ((argb >> 8) & 0xFF) * k);
        int b = (int) Math.min(255, (argb & 0xFF) * k);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
