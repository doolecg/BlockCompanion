package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;

import java.util.Optional;

/**
 * The region the player marks: two corners, set one after the other with the corner key (a third press starts a new
 * selection), or each on its own with the selection tool (left click the first, right click the second). Also
 * remembers the dimension it was made in.
 */
public final class Selection {
    /** Largest volume that can be saved at once, to keep a stray corner from freezing the game. */
    public static final long MAX_VOLUME = 64L * 1024 * 1024;

    private BlockPos first, second;
    private String dimension;

    /** Marks the next corner and says which one it was (1 or 2). */
    public int mark(BlockPos pos, String dimension) {
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
