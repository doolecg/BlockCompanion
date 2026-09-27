package io.blockcompanion.core.placement;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** What a player can lock on a placement so it can't be knocked out of place by accident. */
public enum PlacementLock {
    /** Moving it (scrolling, following the player's moves). */
    POSITION("position"),
    /** Turning it. */
    ROTATION("rotation"),
    /** Mirroring it. */
    MIRROR("mirror"),
    /** Stepping through layers and switching the layer mode. */
    LAYERS("layers");

    public final String label;

    PlacementLock(String label) {
        this.label = label;
    }

    public static final Set<PlacementLock> ALL = Set.copyOf(EnumSet.allOf(PlacementLock.class));
    /** Where and how it is placed: what "locked in place" means. */
    public static final Set<PlacementLock> IN_PLACE = Set.copyOf(EnumSet.of(POSITION, ROTATION, MIRROR));

    public static Optional<PlacementLock> parse(String s) {
        if (s == null) return Optional.empty();
        String t = s.trim().toUpperCase(Locale.ROOT);
        for (PlacementLock l : values()) if (l.name().equals(t)) return Optional.of(l);
        return Optional.empty();
    }

    /** "Locked: position, rotation" style summary; "Unlocked" when empty. */
    public static String describe(Set<PlacementLock> locks) {
        if (locks.isEmpty()) return "Unlocked";
        if (locks.containsAll(ALL)) return "Locked";
        if (locks.containsAll(IN_PLACE) && locks.size() == IN_PLACE.size()) return "Locked in place";
        StringBuilder b = new StringBuilder("Locked: ");
        boolean first = true;
        for (PlacementLock l : values()) {
            if (!locks.contains(l)) continue;
            if (!first) b.append(", ");
            first = false;
            b.append(l.label);
        }
        return b.toString();
    }
}
