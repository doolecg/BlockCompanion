package io.blockcompanion.core.placement;

import java.util.Locale;

/**
 * What the move modifier (Shift by default) plus scroll does while the selection tool is in hand and the player looks at
 * a placement's box (or the save selection, which only moves). Ctrl+Shift+scroll switches between them; the tool panel
 * on the HUD shows the current one. Turning has its own modifier (Ctrl+scroll), and a placement's view (layers, only
 * this one, hidden) its own key. Old saved modes (ROTATE, LAYER, VISIBILITY) load as {@link #MOVE}.
 */
public enum ToolMode {
    /** Push the box away or pull it closer along the axis of the face looked at. */
    MOVE("Move", "move", "Pushes the box away or pulls it closer"),
    /** Mirror it (any notch flips it). */
    MIRROR("Mirror", "mirror", "Flips the box");

    /** The mode's name, "Move". */
    public final String label;
    /** What the scroll does, for a controls line: "Shift+scroll: move". */
    public final String verb;
    /** One line on what it does, sentence case, no full stop. */
    public final String hint;

    ToolMode(String label, String verb, String hint) {
        this.label = label;
        this.verb = verb;
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
