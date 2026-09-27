package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementHistoryTest {
    private static Placement placement() {
        Structure s = new Structure();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 5; z++) s.set(x, y, z, BlockState.of("stone"));
        return new Placement("s", s, new BlockPos(10, 64, 10));
    }

    /** A placement, its layer view, locks and visibility, recorded the way the client does. */
    private static final class Model {
        final Placement p = placement();
        final Layers layers = new Layers();
        final Set<PlacementLock> locks = EnumSet.noneOf(PlacementLock.class);
        boolean visible = true;
        final PlacementHistory history;
        long now = 0;

        Model(PlacementHistory history) {
            this.history = history;
        }

        PlacementHistory.State state() {
            return PlacementHistory.State.of(p, layers, locks, visible);
        }

        void edit(Runnable change) {
            PlacementHistory.State before = state();
            change.run();
            history.record(before, state(), now);
            now += 5_000;
        }

        PlacementHistory.Step undo() {
            PlacementHistory.Step s = history.undo(state());
            if (s != null && s.applied()) apply(s.target());
            return s;
        }

        PlacementHistory.Step redo() {
            PlacementHistory.Step s = history.redo(state());
            if (s != null && s.applied()) apply(s.target());
            return s;
        }

        private void apply(PlacementHistory.State t) {
            t.applyTo(p, layers, locks);
            visible = t.visible();
        }
    }

    @Test
    void undoAndRedoMovesTurnsAndMirrors() {
        Model m = new Model(new PlacementHistory());
        PlacementHistory.State start = m.state();
        m.edit(() -> m.p.move(3, 0, 0));
        PlacementHistory.State moved = m.state();
        m.edit(() -> m.p.rotate(1));
        PlacementHistory.State turned = m.state();
        m.edit(m.p::toggleMirror);
        PlacementHistory.State mirrored = m.state();

        assertThat(m.undo().kind()).isEqualTo(PlacementHistory.Kind.MIRROR);
        assertThat(m.state()).isEqualTo(turned);
        assertThat(m.undo().kind()).isEqualTo(PlacementHistory.Kind.ROTATION);
        assertThat(m.state()).isEqualTo(moved);
        assertThat(m.undo().kind()).isEqualTo(PlacementHistory.Kind.POSITION);
        assertThat(m.state()).isEqualTo(start);
        assertThat(m.undo()).isNull();

        m.redo();
        m.redo();
        assertThat(m.state()).isEqualTo(turned);
        m.redo();
        assertThat(m.state()).isEqualTo(mirrored);
        assertThat(m.redo()).isNull();
    }

    @Test
    void layersLocksAndVisibilityAreUndone() {
        Model m = new Model(new PlacementHistory());
        m.edit(() -> m.layers.step(1, 4));
        m.edit(() -> m.locks.addAll(PlacementLock.IN_PLACE));
        m.edit(() -> m.visible = false);

        m.undo();
        assertThat(m.visible).isTrue();
        m.undo();
        assertThat(m.locks).isEmpty();
        m.undo();
        assertThat(m.layers.showsAll()).isTrue();
    }

    @Test
    void aNewChangeClearsRedo() {
        Model m = new Model(new PlacementHistory());
        m.edit(() -> m.p.move(1, 0, 0));
        m.undo();
        assertThat(m.history.canRedo()).isTrue();
        m.edit(() -> m.p.move(0, 0, 1));
        assertThat(m.history.canRedo()).isFalse();
        assertThat(m.redo()).isNull();
    }

    @Test
    void quickChangesOfOneKindMergeIntoOneStep() {
        PlacementHistory h = new PlacementHistory(64, 800);
        Model m = new Model(h);
        BlockPos start = m.p.origin();
        for (int i = 0; i < 10; i++) {
            PlacementHistory.State before = m.state();
            m.p.move(1, 0, 0);
            h.record(before, m.state(), 1000 + i * 100L);
        }
        assertThat(h.undoSize()).isEqualTo(1);
        m.undo();
        assertThat(m.p.origin()).isEqualTo(start);

        // A different kind, or a pause, starts a new step.
        PlacementHistory.State before = m.state();
        m.p.move(1, 0, 0);
        h.record(before, m.state(), 10_000);
        before = m.state();
        m.p.rotate(1);
        h.record(before, m.state(), 10_050);
        before = m.state();
        m.p.rotate(1);
        h.record(before, m.state(), 20_000);
        assertThat(h.undoSize()).isEqualTo(3);
    }

    @Test
    void noChangeRecordsNothing() {
        PlacementHistory h = new PlacementHistory();
        Model m = new Model(h);
        h.record(m.state(), m.state(), 0);
        assertThat(h.canUndo()).isFalse();
    }

    @Test
    void depthIsBounded() {
        PlacementHistory h = new PlacementHistory(5, 0);
        Model m = new Model(h);
        for (int i = 0; i < 20; i++) m.edit(() -> m.p.move(1, 0, 0));
        assertThat(h.undoSize()).isEqualTo(5);
        for (int i = 0; i < 5; i++) assertThat(m.undo().applied()).isTrue();
        assertThat(m.undo()).isNull();
        assertThat(m.p.origin()).isEqualTo(new BlockPos(10 + 15, 64, 10));
    }

    @Test
    void undoDoesNotMoveALockedPlacement() {
        Model m = new Model(new PlacementHistory());
        m.edit(() -> m.p.move(4, 0, 0));
        BlockPos moved = m.p.origin();
        // Locked from somewhere that isn't recorded (another client, a setting): the move can't be undone.
        m.locks.add(PlacementLock.POSITION);
        PlacementHistory.Step s = m.undo();
        assertThat(s.applied()).isFalse();
        assertThat(s.blockedBy()).isEqualTo(PlacementLock.POSITION);
        assertThat(m.p.origin()).isEqualTo(moved);
        assertThat(m.history.canUndo()).isTrue();

        // Unlocked, it goes.
        m.locks.clear();
        assertThat(m.undo().applied()).isTrue();
        assertThat(m.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
    }

    @Test
    void undoingTheLockItselfIsAllowedAndThenTheMove() {
        Model m = new Model(new PlacementHistory());
        m.edit(() -> m.p.move(2, 0, 0));
        m.edit(() -> m.locks.addAll(PlacementLock.IN_PLACE));
        assertThat(m.undo().kind()).isEqualTo(PlacementHistory.Kind.LOCKS);
        assertThat(m.locks).isEmpty();
        assertThat(m.undo().applied()).isTrue();
        assertThat(m.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
    }

    @Test
    void turningWithOnlyThePositionLockedStaysUndoable() {
        Model m = new Model(new PlacementHistory());
        m.locks.add(PlacementLock.POSITION);
        m.edit(() -> m.p.rotate(1));
        assertThat(m.undo().applied()).isTrue();
        assertThat(m.p.rotation()).isZero();
    }

    @Test
    void rotationAndLayerLocksAreRespected() {
        Model m = new Model(new PlacementHistory());
        m.edit(() -> m.p.rotate(1));
        m.edit(() -> m.layers.step(1, 4));
        m.locks.add(PlacementLock.LAYERS);
        assertThat(m.undo().blockedBy()).isEqualTo(PlacementLock.LAYERS);
        m.locks.clear();
        m.undo();
        m.locks.add(PlacementLock.ROTATION);
        assertThat(m.undo().blockedBy()).isEqualTo(PlacementLock.ROTATION);
        assertThat(m.p.rotation()).isEqualTo(1);
    }

    @Test
    void toolModesWrapBothWays() {
        assertThat(ToolMode.MOVE.next(1)).isEqualTo(ToolMode.MIRROR);
        assertThat(ToolMode.MOVE.next(-1)).isEqualTo(ToolMode.MIRROR);
        assertThat(ToolMode.MIRROR.next(1)).isEqualTo(ToolMode.MOVE);
        assertThat(ToolMode.parse("mirror", ToolMode.MOVE)).isEqualTo(ToolMode.MIRROR);
        assertThat(ToolMode.parse("nope", ToolMode.MOVE)).isEqualTo(ToolMode.MOVE);
        // Modes that were dropped (turning has Ctrl+scroll, layers and show / hide a key) load as the fallback.
        assertThat(ToolMode.parse("VISIBILITY", ToolMode.MOVE)).isEqualTo(ToolMode.MOVE);
        assertThat(ToolMode.parse("ROTATE", ToolMode.MOVE)).isEqualTo(ToolMode.MOVE);
        // Every mode has a name, a verb for the controls line and a one-line description for the tool panel.
        for (ToolMode m : ToolMode.values()) {
            assertThat(m.label).isNotBlank();
            assertThat(m.verb).isNotBlank();
            assertThat(m.hint).isNotBlank().doesNotEndWith(".");
        }
    }
}
