package io.blockcompanion.core.placement;

import java.util.Locale;

/**
 * What plain scrolling does while the selection tool is in hand and the player looks at a placement's box. The mode
 * modifier (Shift by default) plus scroll steps through them.
 */
public enum ToolMode {
    /** Push the box away or pull it closer along the axis of the face looked at. */
    MOVE("Move", "scroll pushes it away or pulls it closer"),
    /** Turn it 90 degrees (up = clockwise). */
    ROTATE("Turn", "scroll turns it 90°"),
    /** Mirror it (any notch flips it). */
    MIRROR("Mirror", "scroll flips it"),
    /** Step the layer view up and down. */
    LAYER("Layers", "scroll steps through the layers"),
    /** Step through the ways to show it: everything, layers, one layer, only this one, hidden. */
    VISIBILITY("Show / hide", "scroll changes what shows");

    public final String label;
    public final String hint;

    ToolMode(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    private static final ToolMode[] VALUES = values();

    /** The mode {@code steps} along (negative goes back), wrapping around. */
    public ToolMode next(int steps) {
        return VALUES[Math.floorMod(ordinal() + steps, VALUES.length)];
    }

    public static ToolMode parse(String s, ToolMode fallback) {
        if (s == null) return fallback;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
