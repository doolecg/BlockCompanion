package io.blockcompanion.core.hud;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Where each piece of the HUD sits and how big it is, set by dragging it in the HUD editor (like Xaero's minimap). A
 * piece is placed by an anchor on the screen ({@code fx, fy}, fractions of the screen), the point of the piece that
 * sits there ({@code px, py}, fractions of the piece) and a small pixel offset; so a piece in a corner stays in that
 * corner at any window size. Stored in the client config as {@code hud.<piece>.*}.
 */
public final class HudLayout {
    /** The movable pieces. */
    public enum Element {
        /** The info panel: schematic, progress bars, layer, held block, linked chests. Bottom left by default. */
        PANEL("panel", "Info panel", 0, 1, 0, 1, 4, -4, 0.85f),
        /** What the looked-at block should be, small, just left of the crosshair. */
        HINT("hint", "Crosshair hint", 0.5, 0.5, 1, 0.5, -8, 0, 0.75f);

        public final String key, label;
        final Placement defaults;

        Element(String key, String label, double fx, double fy, double px, double py, int ox, int oy, float scale) {
            this.key = key;
            this.label = label;
            this.defaults = new Placement(fx, fy, px, py, ox, oy, scale, true);
        }
    }

    /** One piece's position and size. */
    public record Placement(double fx, double fy, double px, double py, int ox, int oy, float scale, boolean enabled) {
        public Placement {
            fx = clamp(fx, 0, 1);
            fy = clamp(fy, 0, 1);
            px = clamp(px, 0, 1);
            py = clamp(py, 0, 1);
            scale = (float) clamp(Math.round(scale * 20) / 20.0, MIN_SCALE, MAX_SCALE);
        }

        public Placement withScale(float s) {
            return new Placement(fx, fy, px, py, ox, oy, s, enabled);
        }

        public Placement withEnabled(boolean e) {
            return new Placement(fx, fy, px, py, ox, oy, scale, e);
        }

        /**
         * The piece's top left corner in GUI pixels, for a piece {@code w} by {@code h} (already scaled) on a screen
         * {@code sw} by {@code sh}, kept on screen.
         */
        public int[] topLeft(int sw, int sh, int w, int h) {
            int x = (int) Math.round(fx * sw - px * w) + ox;
            int y = (int) Math.round(fy * sh - py * h) + oy;
            x = Math.max(0, Math.min(sw - w, x));
            y = Math.max(0, Math.min(sh - h, y));
            return new int[]{x, y};
        }
    }

    public static final float MIN_SCALE = 0.5f, MAX_SCALE = 3f;

    private final Map<Element, Placement> placements = new EnumMap<>(Element.class);

    public HudLayout() {
        reset();
    }

    public Placement get(Element e) {
        return placements.get(e);
    }

    public void set(Element e, Placement p) {
        placements.put(e, p);
    }

    public void reset() {
        for (Element e : Element.values()) placements.put(e, e.defaults);
    }

    public void reset(Element e) {
        placements.put(e, e.defaults);
    }

    /**
     * Where a piece dragged to top left {@code (x, y)} (GUI pixels, size {@code w} by {@code h}) should be stored: the
     * anchor follows the nearest edge or the middle in each direction, so it keeps to that side on resize.
     */
    public static Placement dropped(Placement old, int x, int y, int w, int h, int sw, int sh) {
        double cx = x + w / 2.0, cy = y + h / 2.0;
        double px = third(cx / sw), py = third(cy / sh);
        // Anchor at the same fraction of the screen as the pivot's side; the rest is the pixel offset.
        double fx = px, fy = py;
        int ox = (int) Math.round(x + px * w - fx * sw);
        int oy = (int) Math.round(y + py * h - fy * sh);
        return new Placement(fx, fy, px, py, ox, oy, old.scale(), old.enabled());
    }

    private static double third(double f) {
        return f < 1 / 3.0 ? 0 : f > 2 / 3.0 ? 1 : 0.5;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    // ---- properties ---------------------------------------------------------------------------------------------------

    public void read(Properties p) {
        for (Element e : Element.values()) {
            Placement d = e.defaults;
            String k = "hud." + e.key + ".";
            placements.put(e, new Placement(num(p, k + "fx", d.fx()), num(p, k + "fy", d.fy()), num(p, k + "px", d.px()), num(p, k + "py", d.py()),
                    (int) num(p, k + "ox", d.ox()), (int) num(p, k + "oy", d.oy()), (float) num(p, k + "scale", d.scale()),
                    !"false".equalsIgnoreCase(p.getProperty(k + "enabled", Boolean.toString(d.enabled())).trim())));
        }
    }

    public void write(Properties p) {
        placements.forEach((e, v) -> {
            String k = "hud." + e.key + ".";
            p.setProperty(k + "fx", fmt(v.fx()));
            p.setProperty(k + "fy", fmt(v.fy()));
            p.setProperty(k + "px", fmt(v.px()));
            p.setProperty(k + "py", fmt(v.py()));
            p.setProperty(k + "ox", Integer.toString(v.ox()));
            p.setProperty(k + "oy", Integer.toString(v.oy()));
            p.setProperty(k + "scale", fmt(v.scale()));
            p.setProperty(k + "enabled", Boolean.toString(v.enabled()));
        });
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static double num(Properties p, String key, double def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
