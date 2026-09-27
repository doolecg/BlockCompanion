package io.blockcompanion.core.placement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;

/**
 * Undo and redo across every placement, like an ordinary program: one timeline, so undo steps back through the last
 * changes in the order they were made, whichever placement each was on, and redo steps forward again. A new change clears
 * what could be redone. Each placement keeps its own {@link PlacementHistory} (what a step puts back, locks, merging quick
 * changes); this only remembers the order.
 *
 * @param <T> a placement
 */
public final class UndoTimeline<T> {
    public static final int DEFAULT_DEPTH = 128;

    /** What an undo or redo did: the placement it was on and the step (see {@link PlacementHistory.Step#applied()}). */
    public record Result<T>(T target, PlacementHistory.Step step) {
    }

    private final Function<T, PlacementHistory> histories;
    private final int depth;
    private final Deque<T> undo = new ArrayDeque<>();
    private final Deque<T> redo = new ArrayDeque<>();
    private T last;

    public UndoTimeline(Function<T, PlacementHistory> histories) {
        this(histories, DEFAULT_DEPTH);
    }

    public UndoTimeline(Function<T, PlacementHistory> histories, int depth) {
        if (depth < 1) throw new IllegalArgumentException("depth must be at least 1");
        this.histories = histories;
        this.depth = depth;
    }

    /** A change to {@code target} from {@code before} to {@code after} at {@code now} (milliseconds). */
    public void record(T target, PlacementHistory.State before, PlacementHistory.State after, long now) {
        if (after.diff(before) == null) return;
        PlacementHistory h = histories.apply(target);
        // Quick changes only merge while they stay on one placement, so the order across placements holds.
        if (target != last) h.breakMerge();
        for (T t : redo) histories.apply(t).clearRedo();
        redo.clear();
        if (h.record(before, after, now)) {
            undo.push(target);
            while (undo.size() > depth) undo.removeLast();
        }
        last = target;
    }

    /** Undoes the last change; null when there is nothing to undo. */
    public Result<T> undo(Function<T, PlacementHistory.State> state) {
        return move(undo, redo, false, state);
    }

    /** Redoes the last undone change; null when there is nothing to redo. */
    public Result<T> redo(Function<T, PlacementHistory.State> state) {
        return move(redo, undo, true, state);
    }

    private Result<T> move(Deque<T> from, Deque<T> to, boolean isRedo, Function<T, PlacementHistory.State> state) {
        while (!from.isEmpty()) {
            T t = from.peek();
            PlacementHistory h = histories.apply(t);
            PlacementHistory.Step step = isRedo ? h.redo(state.apply(t)) : h.undo(state.apply(t));
            if (step == null) {
                // Its steps fell off the end or were put back some other way: try the one before.
                from.pop();
                continue;
            }
            if (step.applied()) {
                from.pop();
                to.push(t);
                while (to.size() > depth) to.removeLast();
            }
            // Refused by a lock: it stays, so unlocking and trying again works.
            last = null;
            return new Result<>(t, step);
        }
        return null;
    }

    /** A placement is gone (unloaded): its steps leave the timeline. */
    public void forget(T target) {
        undo.removeIf(t -> t == target);
        redo.removeIf(t -> t == target);
        if (last == target) last = null;
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
        last = null;
    }
}
