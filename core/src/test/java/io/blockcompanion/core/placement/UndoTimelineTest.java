package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UndoTimelineTest {
    /** A placement with its layer view, locks and visibility, as the client keeps them. */
    private static final class Model {
        final Placement p;
        final Layers layers = new Layers();
        final Set<PlacementLock> locks = EnumSet.noneOf(PlacementLock.class);
        final PlacementHistory history = new PlacementHistory();
        boolean visible = true;

        Model(String name) {
            Structure s = new Structure();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 5; z++) s.set(x, y, z, BlockState.of("stone"));
            p = new Placement(name, s, new BlockPos(10, 64, 10));
        }

        PlacementHistory.State state() {
            return PlacementHistory.State.of(p, layers, locks, visible);
        }

        void apply(PlacementHistory.State t) {
            t.applyTo(p, layers, locks);
            visible = t.visible();
        }
    }

    private final Model a = new Model("a"), b = new Model("b");
    private final UndoTimeline<Model> timeline = new UndoTimeline<>(m -> m.history);
    private long now;

    private void edit(Model m, Runnable change, long gapMs) {
        PlacementHistory.State before = m.state();
        change.run();
        timeline.record(m, before, m.state(), now);
        now += gapMs;
    }

    private void edit(Model m, Runnable change) {
        edit(m, change, 5_000);
    }

    private UndoTimeline.Result<Model> undo() {
        UndoTimeline.Result<Model> r = timeline.undo(Model::state);
        if (r != null && r.step().applied()) r.target().apply(r.step().target());
        return r;
    }

    private UndoTimeline.Result<Model> redo() {
        UndoTimeline.Result<Model> r = timeline.redo(Model::state);
        if (r != null && r.step().applied()) r.target().apply(r.step().target());
        return r;
    }

    @Test
    void undoStepsBackAcrossPlacementsInOrder() {
        edit(a, () -> a.p.moveTo(new BlockPos(11, 64, 10)));
        edit(b, () -> b.p.moveTo(new BlockPos(20, 64, 10)));
        edit(a, () -> a.p.toggleMirror());

        assertThat(undo().target()).isSameAs(a);
        assertThat(a.p.mirrored()).isFalse();
        assertThat(undo().target()).isSameAs(b);
        assertThat(b.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
        assertThat(undo().target()).isSameAs(a);
        assertThat(a.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
        assertThat(undo()).isNull();

        assertThat(redo().target()).isSameAs(a);
        assertThat(a.p.origin()).isEqualTo(new BlockPos(11, 64, 10));
        assertThat(redo().target()).isSameAs(b);
        assertThat(b.p.origin()).isEqualTo(new BlockPos(20, 64, 10));
    }

    @Test
    void aNewChangeClearsRedoEverywhere() {
        edit(a, () -> a.p.moveTo(new BlockPos(11, 64, 10)));
        edit(b, () -> b.p.moveTo(new BlockPos(20, 64, 10)));
        undo();
        undo();
        edit(b, () -> b.p.toggleMirror());

        assertThat(timeline.redoSize()).isZero();
        assertThat(redo()).isNull();
        assertThat(a.history.canRedo()).isFalse();
        assertThat(b.history.canRedo()).isFalse();
    }

    @Test
    void quickChangesOnlyMergeOnOnePlacement() {
        // A scroll burst on a is one step...
        edit(a, () -> a.p.moveTo(new BlockPos(11, 64, 10)), 100);
        edit(a, () -> a.p.moveTo(new BlockPos(12, 64, 10)), 100);
        // ...but a change on b in between starts a new one on a.
        edit(b, () -> b.p.moveTo(new BlockPos(20, 64, 10)), 100);
        edit(a, () -> a.p.moveTo(new BlockPos(13, 64, 10)), 100);

        assertThat(timeline.undoSize()).isEqualTo(3);
        undo();
        assertThat(a.p.origin()).isEqualTo(new BlockPos(12, 64, 10));
        undo();
        assertThat(b.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
        undo();
        assertThat(a.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
    }

    @Test
    void aLockedStepStaysUntilUnlocked() {
        edit(a, () -> a.p.moveTo(new BlockPos(11, 64, 10)));
        a.locks.add(PlacementLock.POSITION);

        UndoTimeline.Result<Model> r = undo();
        assertThat(r.step().applied()).isFalse();
        assertThat(r.step().blockedBy()).isEqualTo(PlacementLock.POSITION);
        assertThat(a.p.origin()).isEqualTo(new BlockPos(11, 64, 10));

        a.locks.clear();
        assertThat(undo().step().applied()).isTrue();
        assertThat(a.p.origin()).isEqualTo(new BlockPos(10, 64, 10));
    }

    @Test
    void anUnloadedPlacementLeavesTheTimeline() {
        edit(a, () -> a.p.moveTo(new BlockPos(11, 64, 10)));
        edit(b, () -> b.p.moveTo(new BlockPos(20, 64, 10)));
        timeline.forget(b);

        assertThat(undo().target()).isSameAs(a);
        assertThat(undo()).isNull();
    }

    @Test
    void selectionMovesShareTheTimeline() {
        // The client's timeline holds placements and the selection: each target brings its own history.
        Selection sel = new Selection();
        sel.set(1, new BlockPos(0, 64, 0), "minecraft:overworld");
        sel.set(2, new BlockPos(3, 66, 3), "minecraft:overworld");
        UndoTimeline<Object> mixed = new UndoTimeline<>(t -> t == sel ? sel.history() : ((Model) t).history);

        PlacementHistory.State before = a.state();
        a.p.moveTo(new BlockPos(11, 64, 10));
        mixed.record(a, before, a.state(), 0);
        before = sel.state();
        sel.move(new BlockPos(0, 0, 2));
        mixed.record(sel, before, sel.state(), 10_000);

        UndoTimeline.Result<Object> r = mixed.undo(t -> t == sel ? sel.state() : ((Model) t).state());
        assertThat(r.target()).isSameAs(sel);
        sel.apply(r.step().target());
        assertThat(sel.box()).contains(new io.blockcompanion.core.model.Box(0, 64, 0, 3, 66, 3));
        assertThat(r.step().kind()).isEqualTo(PlacementHistory.Kind.POSITION);

        r = mixed.redo(t -> t == sel ? sel.state() : ((Model) t).state());
        assertThat(r.target()).isSameAs(sel);
        sel.apply(r.step().target());
        assertThat(sel.first()).isEqualTo(new BlockPos(0, 64, 2));

        // Marking a new corner drops the selection's steps: undo goes on to the placement.
        sel.set(1, new BlockPos(9, 64, 9), "minecraft:overworld");
        r = mixed.undo(t -> t == sel ? sel.state() : ((Model) t).state());
        assertThat(r.target()).isSameAs(a);
        assertThat(sel.first()).isEqualTo(new BlockPos(9, 64, 9));
    }
}
