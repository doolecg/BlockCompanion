package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;

import java.util.Optional;
import java.util.Set;

/**
 * The region the player marks: two corners, set one after the other with the corner key (a third press starts a new
 * selection), or each on its own with the selection tool (left click the first, right click the second). Also
 * remembers the dimension it was made in.
 *
 * <p>The whole selection can be moved ({@link #move}) and those moves undone: its {@link #history()} holds them as
 * {@link PlacementHistory} steps of the box's lowest corner ({@link #state()}, {@link #apply}). Marking or clearing a
 * corner starts it over, so an old move never shifts a new selection.
 */
public final class Selection {
    /** Largest volume that can be saved at once, to keep a stray corner from freezing the game. */
    public static final long MAX_VOLUME = 64L * 1024 * 1024;

    private BlockPos first, second;
    private String dimension;
    private final PlacementHistory history = new PlacementHistory();

    /** Marks the next corner and says which one it was (1 or 2). */
    public int mark(BlockPos pos, String dimension) {
        history.clear();
        if (first == null || second != null || !dimension.equals(this.dimension)) {
            first = pos;
            second = null;
            this.dimension = dimension;
            return 1;
        }
        second = pos;
        return 2;
    }

    /** Sets corner 1 or 2 directly; a corner in another dimension than the other one clears that one. */
    public void set(int corner, BlockPos pos, String dimension) {
        history.clear();
        if (!dimension.equals(this.dimension)) {
            first = second = null;
            this.dimension = dimension;
        }
        if (corner == 1) first = pos;
        else second = pos;
    }

    public void clear() {
        first = second = null;
        dimension = null;
        history.clear();
    }

    /** Moves both corners (the one set, with one) by {@code delta}; false when there is nothing to move. */
    public boolean move(BlockPos delta) {
        if (isEmpty()) return false;
        if (first != null) first = first.add(delta);
        if (second != null) second = second.add(delta);
        return true;
    }

    /** The moves made with {@link #move}, for undo and redo; cleared whenever a corner is marked or the selection cleared. */
    public PlacementHistory history() {
        return history;
    }

    /**
     * Where the selection is, for undo: a {@link PlacementHistory.State} whose origin is the box's lowest corner (the
     * world origin while nothing is marked) and nothing else set.
     */
    public PlacementHistory.State state() {
        BlockPos min = box().map(Box::min).orElse(BlockPos.ORIGIN);
        return new PlacementHistory.State(min, 0, false, Set.of(), -1, Layers.Mode.BUILD_UP, true);
    }

    /** Moves the selection so the box's lowest corner is at {@code state}'s origin (an undo or redo); nothing while empty. */
    public void apply(PlacementHistory.State state) {
        Box b = box().orElse(null);
        if (b == null) return;
        move(state.origin().subtract(b.min()));
    }

    public BlockPos first() {
        return first;
    }

    public BlockPos second() {
        return second;
    }

    public String dimension() {
        return dimension;
    }

    public boolean isEmpty() {
        return first == null && second == null;
    }

    public boolean isComplete() {
        return first != null && second != null;
    }

    /** The box between the corners (inclusive); with one corner set, that single block. Empty with none. */
    public Optional<Box> box() {
        if (first == null && second == null) return Optional.empty();
        return Optional.of(Box.of(first == null ? second : first, second == null ? first : second));
    }
}
