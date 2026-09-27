package io.blockcompanion.core.hud;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The colours the player can change: ghosts, wrong and in-the-way blocks, boxes and outlines. Each is an RGB colour
 * ({@code 0xRRGGBB}); the renderers add their own opacity. Kept in the config as {@code color.<key>=#RRGGBB}.
 */
public final class Palette {
    /** One changeable colour. */
    public enum Entry {
        GHOST("ghost", "Ghost tint", "The tint on missing blocks while Shimmer is on.", 0xDBEDFF),
        WRONG("wrong", "Wrong block", "Fill and outline over a block that doesn't match.", 0xFF3030),
        EXTRA("extra", "In the way", "Fill and outline over a block where the schematic has air.", 0xFFA030),
        HELPER("helper", "Material helper", "The marks on ghosts that need the block you hold.", 0xFFD75A),
        BLOCK_ENTITY("blockEntity", "Chest & sign outline", "The faint outline around chest, sign and bed ghosts.", 0x80E0FF),
        BOX("box", "Box", "The bounding box of the selected schematic.", 0xFFFFFF),
        BOX_HOVER("boxHover", "Box, looked at", "The box you are looking at (it moves and turns with scrolling).", 0xFFE066),
        BOX_LOCKED("boxLocked", "Box, locked", "The box of a schematic locked in place.", 0x8FC7FF),
        SELECTION("selection", "Save selection", "The outline between the two corners marked for saving.", 0x4FE3E3);

        public final String key, label, description;
        public final int defaultRgb;

        Entry(String key, String label, String description, int defaultRgb) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.defaultRgb = defaultRgb;
        }
    }

    /** A named set of colours the settings screen can apply at once. */
    public record Preset(String name, int[] rgb) {
    }

    public static final Preset DEFAULT = new Preset("Default", defaults());
    /** Blue and yellow instead of red and orange, told apart with every common colour blindness. */
    public static final Preset COLOR_BLIND = new Preset("Colour-blind safe", with(defaults(),
            Entry.WRONG, 0xD055FF, Entry.EXTRA, 0x3A9BFF, Entry.HELPER, 0xFFE14A, Entry.BOX_HOVER, 0xFFFFFF, Entry.BOX, 0xB0B0B0));
    /** Strong, saturated colours that stay visible in bright or busy builds. */
    public static final Preset VIVID = new Preset("Vivid", with(defaults(),
            Entry.GHOST, 0xB8DCFF, Entry.WRONG, 0xFF0040, Entry.EXTRA, 0xFF7A00, Entry.HELPER, 0x40FF60, Entry.BOX_HOVER, 0xFFF000,
            Entry.SELECTION, 0x00FFFF));
    /** Soft, low-contrast colours. */
    public static final Preset SOFT = new Preset("Soft", with(defaults(),
            Entry.GHOST, 0xEEF3F8, Entry.WRONG, 0xE07A7A, Entry.EXTRA, 0xE0B070, Entry.HELPER, 0xE8D8A0, Entry.BOX, 0xDDDDDD,
            Entry.BOX_HOVER, 0xF0E0A0, Entry.SELECTION, 0x9AD8D8));
    public static final List<Preset> PRESETS = List.of(DEFAULT, COLOR_BLIND, VIVID, SOFT);

    /** Quick picks in the colour editor. */
    public static final List<Integer> SWATCHES = List.of(0xFFFFFF, 0xB0B0B0, 0xFF3030, 0xFFA030, 0xFFE066, 0x5DBE4A, 0x4FE3E3, 0x3A9BFF,
            0x8FC7FF, 0xD055FF, 0xFF6FB5, 0xDBEDFF);

    private final int[] rgb = defaults();

    private static int[] defaults() {
        int[] a = new int[Entry.values().length];
        for (Entry e : Entry.values()) a[e.ordinal()] = e.defaultRgb;
        return a;
    }

    private static int[] with(int[] base, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) base[((Entry) pairs[i]).ordinal()] = (Integer) pairs[i + 1];
        return base;
    }

    public int get(Entry e) {
        return rgb[e.ordinal()];
    }

    /** {@code e}'s colour with the given opacity (0 to 255) on top, as ARGB. */
    public int argb(Entry e, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | get(e);
    }

    /** Red, green and blue of {@code e} from 0 to 1. */
    public float[] floats(Entry e) {
        int c = get(e);
        return new float[]{((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f};
    }

    public void set(Entry e, int value) {
        rgb[e.ordinal()] = value & 0xFFFFFF;
    }

    public void apply(Preset p) {
        System.arraycopy(p.rgb(), 0, rgb, 0, rgb.length);
    }

    /** The preset these colours match exactly, or null when they have been changed. */
    public Preset matchingPreset() {
        for (Preset p : PRESETS) if (Arrays.equals(p.rgb(), rgb)) return p;
        return null;
    }

    public boolean isDefault(Entry e) {
        return get(e) == e.defaultRgb;
    }

    public void read(Properties p) {
        for (Entry e : Entry.values()) {
            Integer v = parseHex(p.getProperty("color." + e.key));
            rgb[e.ordinal()] = v != null ? v : e.defaultRgb;
        }
    }

    public void write(Properties p) {
        for (Entry e : Entry.values()) p.setProperty("color." + e.key, hex(get(e)));
    }

    /** {@code #RRGGBB}. */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Reads {@code #RRGGBB}, {@code RRGGBB} or {@code #RGB}; null when it isn't a colour. */
    public static Integer parseHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.length() == 3) s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        if (s.length() != 6) return null;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
