package io.blockcompanion.core.placement;

/**
 * The slice view, as in BlockDesigner: show every level, or step through the schematic's Y levels. Levels either build
 * up (the current one and everything below it, the default) or show one level alone.
 *
 * <p>The level is counted from the bottom of the schematic (0 = its lowest layer), so moving the placement up or down
 * keeps the same slice.
 */
public final class Layers {
    public enum Mode {
        /** Current level and everything below it. */
        BUILD_UP,
        /** Only the current level. */
        SINGLE
    }

    /** Null while every level shows. */
    private Integer level;
    private Mode mode = Mode.BUILD_UP;
    private long version;

    public boolean showsAll() {
        return level == null;
    }

    /** The current level (0 = bottom), or -1 while every level shows. */
    public int level() {
        return level == null ? -1 : level;
    }

    public Mode mode() {
        return mode;
    }

    /** Bumps whenever what's visible changes. */
    public long version() {
        return version;
    }

    /** True if a level (0 = bottom of the schematic) is visible. */
    public boolean isVisible(int localY) {
        if (level == null) return true;
        return mode == Mode.SINGLE ? localY == level : localY <= level;
    }

    /** Lowest visible level, for culling whole sections. */
    public int minVisible() {
        return level == null || mode == Mode.BUILD_UP ? Integer.MIN_VALUE : level;
    }

    /** Highest visible level. */
    public int maxVisible() {
        return level == null ? Integer.MAX_VALUE : level;
    }

    /**
     * PgUp ({@code dir > 0}) / PgDn ({@code dir < 0}) for a schematic {@code height} levels tall. From the full view, up
     * starts at the bottom level and down hides the top one; building up past the top returns to the full view.
     */
    public void step(int dir, int height) {
        if (height <= 0 || dir == 0) return;
        int max = height - 1;
        if (level == null) {
            level = clamp(dir > 0 ? 0 : mode == Mode.SINGLE ? max : max - 1, max);
        } else if (mode == Mode.BUILD_UP && dir > 0 && level + 1 > max) {
            level = null;
        } else {
            level = clamp(level + Integer.signum(dir), max);
        }
        version++;
    }

    /**
     * Insert: switches between one level and building up. From the full view it starts slicing at {@code startLevel}
     * (e.g. the level the player stands on), clamped to the schematic.
     */
    public void toggleMode(int startLevel, int height) {
        mode = mode == Mode.SINGLE ? Mode.BUILD_UP : Mode.SINGLE;
        if (level == null && height > 0) level = clamp(startLevel, height - 1);
        version++;
    }

    /** Back to every level (mode kept). */
    public void showAll() {
        if (level == null) return;
        level = null;
        version++;
    }

    /** Restores a saved state; {@code level < 0} means every level. */
    public void set(int level, Mode mode) {
        this.level = level < 0 ? null : level;
        this.mode = mode == null ? Mode.BUILD_UP : mode;
        version++;
    }

    /** Keeps the level inside a schematic that changed height. */
    public void clampTo(int height) {
        if (level != null && height > 0 && level > height - 1) {
            level = height - 1;
            version++;
        }
    }

    /** HUD text, or null while every level shows. {@code worldY} is the level's world height. */
    public String describe(int worldY) {
        if (level == null) return null;
        return (mode == Mode.SINGLE ? "Layer " : "Layers up to ") + (level + 1) + " (Y " + worldY + ")";
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}
