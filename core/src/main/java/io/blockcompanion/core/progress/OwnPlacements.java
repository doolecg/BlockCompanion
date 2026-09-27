package io.blockcompanion.core.progress;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Which block changes the player made: a change counts as theirs when it is at (or next to) a block they right-clicked
 * a moment ago. The "wrong block" note plays only for those, so grass spreading, crops growing, a furnace lighting up
 * or someone else building nearby stays quiet.
 */
public final class OwnPlacements {
    /** How long after a click a change still counts as its result (server round trip included). */
    public static final long WINDOW_MS = 1500;
    private static final int KEEP = 16;

    private record Click(int x, int y, int z, long at) {
    }

    private final ArrayDeque<Click> recent = new ArrayDeque<>();

    /** The player right-clicked a block, or easy place aimed at a cell: changes around it are theirs for a moment. */
    public void clicked(int x, int y, int z, long now) {
        recent.addLast(new Click(x, y, z, now));
        while (recent.size() > KEEP) recent.removeFirst();
    }

    /**
     * True when a change at this cell follows a recent click: at most one block away (the clicked block, the cell next
     * to the face, the other half of a door or bed).
     */
    public boolean mine(int x, int y, int z, long now) {
        for (Iterator<Click> it = recent.descendingIterator(); it.hasNext(); ) {
            Click c = it.next();
            if (now - c.at > WINDOW_MS) break;
            if (Math.abs(c.x - x) <= 1 && Math.abs(c.y - y) <= 1 && Math.abs(c.z - z) <= 1) return true;
        }
        return false;
    }

    public void clear() {
        recent.clear();
    }
}
