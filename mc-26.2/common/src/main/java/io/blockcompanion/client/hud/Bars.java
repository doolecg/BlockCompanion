package io.blockcompanion.client.hud;

import io.blockcompanion.core.hud.Colors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Progress bars drawn like the game's own (the experience bar and the boss bars): a thin dark track with a black edge,
 * the fill lit along its top and shaded along its bottom. The fill runs red, orange, yellow, green from left to right,
 * so a bar's colour says how far along it is.
 */
public final class Bars {
    public static final int HEIGHT = 5;

    private Bars() {
    }

    /** A bar {@code w} wide at {@code (x, y)}, filled to {@code fraction} with the red-to-green gradient. */
    public static void gradient(GuiGraphicsExtractor g, int x, int y, int w, double fraction) {
        track(g, x, y, w);
        int filled = (int) Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
        for (int i = 0; i < filled; i++) {
            int c = Colors.gradient(w <= 3 ? 1 : i / (double) (w - 3));
            column(g, x + 1 + i, y, c);
        }
    }

    /** The same bar in one colour (a finished build, a hint). */
    public static void solid(GuiGraphicsExtractor g, int x, int y, int w, double fraction, int argb) {
        track(g, x, y, w);
        int filled = (int) Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
        for (int i = 0; i < filled; i++) column(g, x + 1 + i, y, argb);
    }

    private static void track(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x, y, x + w, y + HEIGHT, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + HEIGHT - 1, 0xFF3A3A3A);
        g.fill(x + 1, y + HEIGHT - 2, x + w - 1, y + HEIGHT - 1, 0xFF2A2A2A);
    }

    private static void column(GuiGraphicsExtractor g, int x, int y, int argb) {
        g.fill(x, y + 1, x + 1, y + 2, Colors.shade(argb, 1.3));
        g.fill(x, y + 2, x + 1, y + HEIGHT - 2, argb);
        g.fill(x, y + HEIGHT - 2, x + 1, y + HEIGHT - 1, Colors.shade(argb, 0.7));
    }
}
