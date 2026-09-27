package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;

/**
 * Undo and redo for one placement: where it is, how it's turned, what is locked, its layer view and whether it shows.
 * The client records the state before each change it makes ({@link #record}); {@link #undo} and {@link #redo} hand back
 * the state to put the placement in. Quick changes of the same kind in a row (scrolling it along, stepping layers) make
 * one step. The depth is bounded: the oldest steps fall off.
 *
 * <p>A step only puts back what it changed (a move puts back the position, not the locks or whether it shows). Locks are
 * respected: a step that would move, turn, mirror or re-slice a placement whose lock for that is on is refused and stays
 * on the stack, so unlocking and trying again works. Undoing a lock change itself is always allowed.
 */
public final class PlacementHistory {
    public static final int DEFAULT_DEPTH = 64;
    /** Changes of the same kind closer together than this merge into one step. */
    public static final long DEFAULT_COALESCE_MS = 800;

    /** Everything about a placement that undo puts back. */
    public record State(BlockPos origin, int rotation, boolean mirrored, Set<PlacementLock> locks, int level, Layers.Mode mode,
                        boolean visible) {
        public State {
            rotation = Math.floorMod(rotation, 4);
            locks = locks.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(locks));
            if (level < 0) level = -1;
            if (mode == null) mode = Layers.Mode.BUILD_UP;
        }

        public static State of(Placement p, Layers layers, Set<PlacementLock> locks, boolean visible) {
            return new State(p.origin(), p.rotation(), p.mirrored(), locks, layers.level(), layers.mode(), visible);
        }

        /** Puts the placement, its layer view and its locks in this state (whether it shows is the caller's). */
        public void applyTo(Placement p, Layers layers, Set<PlacementLock> lockSet) {
            if (p.rotation() != rotation || p.mirrored() != mirrored) p.setOrientation(rotation, mirrored);
            p.moveTo(origin);
            if (layers.level() != level || layers.mode() != mode) layers.set(level, mode);
            if (!lockSet.equals(locks)) {
                lockSet.clear();
                lockSet.addAll(locks);
            }
        }

        /** What differs from {@code other}; null when nothing does. */
        public Kind diff(State other) {
            Kind k = null;
            boolean turned = rotation != other.rotation, flipped = mirrored != other.mirrored;
            if (turned) k = Kind.ROTATION;
            if (flipped) k = merge(k, Kind.MIRROR);
            // Turning keeps the box's centre, so its corner moves too: that is part of the turn.
            if (!turned && !flipped && !origin.equals(other.origin)) k = merge(k, Kind.POSITION);
            if (level != other.level || mode != other.mode) k = merge(k, Kind.LAYERS);
            if (!locks.equals(other.locks)) k = merge(k, Kind.LOCKS);
            if (visible != other.visible) k = merge(k, Kind.VISIBILITY);
            return k;
        }

        /**
         * This state for what a step of {@code kind} changes, and {@code current} for everything else, so undoing a move
         * doesn't also undo a lock or a hide that happened outside the history.
         */
        public State only(Kind kind, State current) {
            return switch (kind) {
                case POSITION -> new State(origin, current.rotation, current.mirrored, current.locks, current.level, current.mode, current.visible);
                case ROTATION, MIRROR -> new State(origin, rotation, mirrored, current.locks, current.level, current.mode, current.visible);
                case LAYERS -> new State(current.origin, current.rotation, current.mirrored, current.locks, level, mode, current.visible);
                case LOCKS -> new State(current.origin, current.rotation, current.mirrored, locks, current.level, current.mode, current.visible);
                case VISIBILITY -> new State(current.origin, current.rotation, current.mirrored, current.locks, current.level, current.mode, visible);
                case SEVERAL -> this;
            };
        }

        private static Kind merge(Kind a, Kind b) {
            return a == null || a == b ? b : Kind.SEVERAL;
        }
    }

    /** What a step changed, for messages and for merging quick changes. */
    public enum Kind {
        POSITION("move"),
        ROTATION("turn"),
        MIRROR("mirror"),
        LAYERS("layer change"),
        LOCKS("lock change"),
        VISIBILITY("show / hide"),
        SEVERAL("change");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /**
     * The outcome of an undo or redo: the state to apply (null when refused), what the step changes, and the lock that
     * refused it (null when it applies).
     */
    public record Step(State target, Kind kind, PlacementLock blockedBy) {
        public boolean applied() {
            return target != null;
        }
    }

    private record Entry(State state, Kind kind) {
    }

    private final int depth;
    private final long coalesceMs;
    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();
    private Kind lastKind;
    private long lastTime;

    public PlacementHistory() {
        this(DEFAULT_DEPTH, DEFAULT_COALESCE_MS);
    }

    public PlacementHistory(int depth, long coalesceMs) {
        if (depth < 1) throw new IllegalArgumentException("depth must be at least 1");
        this.depth = depth;
        this.coalesceMs = coalesceMs;
    }

    /**
     * A change from {@code before} to {@code after} at time {@code now} (milliseconds). Nothing is recorded when they are
     * the same; a change of the same kind soon after the last one merges into it. Any change clears the redo steps.
     * Returns true when the change became a new step (false when nothing changed or it merged into the last step).
     */
    public boolean record(State before, State after, long now) {
        Kind kind = after.diff(before);
        if (kind == null) return false;
        redo.clear();
        boolean merge = !undo.isEmpty() && kind == lastKind && kind != Kind.LOCKS && kind != Kind.VISIBILITY
                && now - lastTime >= 0 && now - lastTime <= coalesceMs;
        if (!merge) {
            undo.push(new Entry(before, kind));
            while (undo.size() > depth) undo.removeLast();
        }
        lastKind = kind;
        lastTime = now;
        return !merge;
    }

    /** The next change starts a step of its own, however soon it comes (another placement was changed in between). */
    public void breakMerge() {
        lastKind = null;
    }

    /** Drops the redo steps (a change elsewhere made them stale). */
    public void clearRedo() {
        redo.clear();
    }

    /** Steps back from {@code current}; null when there is nothing to undo. */
    public Step undo(State current) {
        return move(undo, redo, current);
    }

    /** Steps forward again from {@code current}; null when there is nothing to redo. */
    public Step redo(State current) {
        return move(redo, undo, current);
    }

    private Step move(Deque<Entry> from, Deque<Entry> to, State current) {
        Entry e = from.peek();
        // Steps that would change nothing any more (it was put back some other way) are skipped.
        while (e != null && e.state.only(e.kind, current).equals(current)) {
            from.pop();
            e = from.peek();
        }
        if (e == null) return null;
        State target = e.state.only(e.kind, current);
        PlacementLock lock = blockedBy(current, target);
        if (lock != null) return new Step(null, e.kind, lock);
        from.pop();
        to.push(new Entry(current, e.kind));
        while (to.size() > depth) to.removeLast();
        // The next change starts a step of its own.
        lastKind = null;
        return new Step(target, e.kind, null);
    }

    /**
     * The lock that forbids going from {@code current} to {@code target}: one that is on in both and covers something
     * the step changes. A step that switches the lock itself is never refused.
     */
    public static PlacementLock blockedBy(State current, State target) {
        boolean turned = current.rotation() != target.rotation(), flipped = current.mirrored() != target.mirrored();
        if (turned && both(current, target, PlacementLock.ROTATION)) return PlacementLock.ROTATION;
        if (flipped && both(current, target, PlacementLock.MIRROR)) return PlacementLock.MIRROR;
        if (!turned && !flipped && !current.origin().equals(target.origin()) && both(current, target, PlacementLock.POSITION)) {
            return PlacementLock.POSITION;
        }
        if ((current.level() != target.level() || current.mode() != target.mode()) && both(current, target, PlacementLock.LAYERS)) {
            return PlacementLock.LAYERS;
        }
        return null;
    }

    private static boolean both(State a, State b, PlacementLock l) {
        return a.locks().contains(l) && b.locks().contains(l);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public int undoSize() {
        return undo.size();
    }

    public int redoSize() {
        return redo.size();
    }

    public void clear() {
        undo.clear();
        redo.clear();
        lastKind = null;
    }
}
