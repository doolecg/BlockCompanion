package io.blockcompanion.core.autobuild;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How one AutoBuild runs, picked by the player before (or while) it builds, like a Schematicannon's settings. The server
 * caps each with its own config ({@link #capped}).
 *
 * @param blocksPerSecond placing speed, {@value #MIN_RATE} to {@value #MAX_RATE}
 * @param replace         what it may break to put the schematic's block there (and whether it clears the schematic's air)
 * @param order           which blocks go first
 * @param ignoreAir       the schematic's air is never touched; off, and with {@link Replace#CLEAR}, blocks where the
 *                        schematic has air are removed
 * @param skipMissing     a block with no items left in the chests is skipped instead of pausing the build
 * @param radius          only blocks within this many blocks of the player are built (it follows the player); 0 builds
 *                        the whole schematic
 * @param onlyItem        only blocks placed with this item are built ("build all of these"), e.g. {@code minecraft:oak_planks};
 *                        empty builds every block
 */
public record AutoBuildOptions(int blocksPerSecond, Replace replace, Order order, boolean ignoreAir, boolean skipMissing, int radius,
                               String onlyItem) {
    public static final int MIN_RATE = 1;
    public static final int MAX_RATE = 1000;
    public static final int MAX_RADIUS = 256;
    /** What the client offers for the speed. */
    public static final int[] RATES = {1, 2, 5, 10, 20, 40, 100, 200};
    /** What the client offers for the radius (0: the whole schematic). */
    public static final int[] RADII = {0, 8, 16, 24, 32, 48, 64, 96, 128};

    /** What AutoBuild may break. Ids are part of the wire format and ordered from safest: only append. */
    public enum Replace {
        /** Never breaks anything: only empty spots (air, grass, water) are built. */
        KEEP("Empty spots only", "Never breaks a block: only empty spots (air, grass, water) are built. A different block in the way is left and counted as skipped."),
        /** Breaks a solid block in the way (stone, planks, glass); leaves plants, torches, signs and containers. */
        SOLID("Replace solid", "Breaks a solid block in the way (stone, dirt, planks) to put the schematic's block there. Plants, torches, signs and anything holding items are left."),
        /** Breaks any block in the way, containers too (their contents spill). Never bedrock, barriers or portals. */
        ALL("Replace all", "Breaks any block in the way, chests and signs too (a chest's contents spill). Never bedrock, barriers, portals or command blocks."),
        /** As {@link #ALL}, and also removes blocks where the schematic has air (unless air is ignored). */
        CLEAR("Replace and clear", "As Replace all, and also clears blocks where the schematic has air (turn Ignore air off for that).");

        public final String label, description;

        Replace(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public boolean breaks() {
            return this != KEEP;
        }

        static Replace byId(long id) {
            return id >= 0 && id < values().length ? values()[(int) id] : KEEP;
        }

        /** "keep", "solid", "all" or "clear" (config files). */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Replace parse(String s, Replace fallback) {
            if (s == null) return fallback;
            for (Replace r : values()) if (r.key().equalsIgnoreCase(s.trim()) || r.name().equalsIgnoreCase(s.trim())) return r;
            // "none", "false" and "off" read as keep; "true" and "on" as the most allowed.
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "none", "false", "off", "no" -> KEEP;
                case "true", "on", "yes" -> CLEAR;
                default -> fallback;
            };
        }
    }

    /** Which blocks go first. Ids are part of the wire format: only append. */
    public enum Order {
        BOTTOM_UP("Bottom up", "Layer by layer from the lowest."),
        TOP_DOWN("Top down", "Layer by layer from the highest. Blocks that need support below (sand, torches) wait for the end."),
        NEAREST("Nearest first", "The blocks closest to you first, following you as you move."),
        BY_BLOCK("By block", "All of one kind of block, then the next kind (each bottom up).");

        public final String label, description;

        Order(String label, String description) {
            this.label = label;
            this.description = description;
        }

        static Order byId(long id) {
            return id >= 0 && id < values().length ? values()[(int) id] : BOTTOM_UP;
        }

        public static Order parse(String s, Order fallback) {
            if (s == null) return fallback;
            for (Order o : values()) if (o.name().equalsIgnoreCase(s.trim())) return o;
            return fallback;
        }
    }

    public AutoBuildOptions {
        blocksPerSecond = Math.max(MIN_RATE, Math.min(MAX_RATE, blocksPerSecond));
        if (replace == null) replace = Replace.KEEP;
        if (order == null) order = Order.BOTTOM_UP;
        radius = Math.max(0, Math.min(MAX_RADIUS, radius));
        onlyItem = onlyItem == null ? "" : onlyItem.trim();
    }

    /** What 0.3.0 did: 5 a second, never breaking, bottom up, the whole schematic, pausing when items run out. */
    public static final AutoBuildOptions DEFAULT = new AutoBuildOptions(5, Replace.KEEP, Order.BOTTOM_UP, true, false, 0, "");

    /** Only the speed set (as from a client that sends only that). */
    public static AutoBuildOptions ofRate(int blocksPerSecond) {
        return DEFAULT.withRate(blocksPerSecond);
    }

    public AutoBuildOptions withRate(int v) {
        return new AutoBuildOptions(v, replace, order, ignoreAir, skipMissing, radius, onlyItem);
    }

    public AutoBuildOptions withReplace(Replace v) {
        return new AutoBuildOptions(blocksPerSecond, v, order, ignoreAir, skipMissing, radius, onlyItem);
    }

    public AutoBuildOptions withOrder(Order v) {
        return new AutoBuildOptions(blocksPerSecond, replace, v, ignoreAir, skipMissing, radius, onlyItem);
    }

    public AutoBuildOptions withIgnoreAir(boolean v) {
        return new AutoBuildOptions(blocksPerSecond, replace, order, v, skipMissing, radius, onlyItem);
    }

    public AutoBuildOptions withSkipMissing(boolean v) {
        return new AutoBuildOptions(blocksPerSecond, replace, order, ignoreAir, v, radius, onlyItem);
    }

    public AutoBuildOptions withRadius(int v) {
        return new AutoBuildOptions(blocksPerSecond, replace, order, ignoreAir, skipMissing, v, onlyItem);
    }

    public AutoBuildOptions withOnlyItem(String v) {
        return new AutoBuildOptions(blocksPerSecond, replace, order, ignoreAir, skipMissing, radius, v);
    }

    /** True when blocks where the schematic has air are removed ("voiding"). */
    public boolean clearsAir() {
        return replace == Replace.CLEAR && !ignoreAir;
    }

    /** True when a block of this kind in the way may be broken. */
    public boolean mayBreak(BuildWorld.Removal what) {
        return switch (what) {
            case SOLID -> replace != Replace.KEEP;
            case OTHER -> replace == Replace.ALL || replace == Replace.CLEAR;
            case NEVER -> false;
        };
    }

    /**
     * Within what a server allows: speed up to {@code maxRate}, breaking no more than {@code maxReplace}, and with a
     * {@code maxRadius} above 0, a radius no larger (and never the whole schematic).
     */
    public AutoBuildOptions capped(int maxRate, Replace maxReplace, int maxRadius) {
        int rate = Math.max(MIN_RATE, Math.min(Math.max(MIN_RATE, maxRate), blocksPerSecond));
        Replace r = replace.ordinal() <= maxReplace.ordinal() ? replace : maxReplace;
        int rad = maxRadius <= 0 ? radius : radius == 0 ? maxRadius : Math.min(radius, maxRadius);
        return new AutoBuildOptions(rate, r, order, ignoreAir, skipMissing, rad, onlyItem);
    }

    /** "20 per second, top down, within 32 blocks, replace solid", for notices and the log (bottom up goes unsaid). */
    public String describe() {
        StringBuilder b = new StringBuilder().append(blocksPerSecond).append(" per second");
        if (order != Order.BOTTOM_UP) b.append(", ").append(order.label.toLowerCase(Locale.ROOT));
        if (radius > 0) b.append(", within ").append(radius).append(" blocks");
        if (replace != Replace.KEEP) b.append(", ").append(replace.label.toLowerCase(Locale.ROOT));
        if (clearsAir()) b.append(" (clearing air)");
        if (skipMissing) b.append(", skipping missing items");
        if (!onlyItem.isEmpty()) b.append(", only ").append(onlyItem.substring(onlyItem.indexOf(':') + 1).replace('_', ' '));
        return b.toString();
    }

    // ---- wire -------------------------------------------------------------------------------------------------------

    /**
     * The numbers, as named entries: a newer side may add keys an older one skips, and a key that is missing reads as
     * its {@link #DEFAULT}. {@link #onlyItem} travels as the one text entry ({@link #ONLY_ITEM}).
     */
    public Map<String, Long> toMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("rate", (long) blocksPerSecond);
        m.put("replace", (long) replace.ordinal());
        m.put("order", (long) order.ordinal());
        m.put("ignore_air", ignoreAir ? 1L : 0L);
        m.put("skip_missing", skipMissing ? 1L : 0L);
        m.put("radius", (long) radius);
        return m;
    }

    /** The key of the text entry holding {@link #onlyItem}. */
    public static final String ONLY_ITEM = "only_item";

    public Map<String, String> toText() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!onlyItem.isEmpty()) m.put(ONLY_ITEM, onlyItem);
        return m;
    }

    public static AutoBuildOptions fromMaps(Map<String, Long> numbers, Map<String, String> text) {
        AutoBuildOptions d = DEFAULT;
        return new AutoBuildOptions(clampInt(numbers.getOrDefault("rate", (long) d.blocksPerSecond)),
                Replace.byId(numbers.getOrDefault("replace", 0L)), Order.byId(numbers.getOrDefault("order", 0L)),
                numbers.getOrDefault("ignore_air", 1L) != 0, numbers.getOrDefault("skip_missing", 0L) != 0,
                clampInt(numbers.getOrDefault("radius", 0L)), text.getOrDefault(ONLY_ITEM, ""));
    }

    private static int clampInt(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }
}
