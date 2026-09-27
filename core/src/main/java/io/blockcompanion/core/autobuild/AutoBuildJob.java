package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.model.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One AutoBuild run: walks the {@link AutoBuildPlan} steps in order and places them at a steady rate, taking each
 * block's items out of the owner's linked chests as it goes (nothing in creative). Driven by {@link #tick} once per
 * server tick; Minecraft-free, so the mods and the Paper plugin run the same one.
 *
 * <ul>
 *     <li>A position that is already right is passed over; one with a different block is never broken: it is skipped
 *     and counted.</li>
 *     <li>A block whose support isn't there yet goes to the end of its layer and is tried once more there; still
 *     unsupported (sand over a hole, a torch with no wall) it is skipped.</li>
 *     <li>A step whose chunk isn't loaded waits ({@link State#WAITING}) until it is.</li>
 *     <li>When the chests run out of an item partway (someone took it), the run pauses and says what is missing.</li>
 * </ul>
 */
public final class AutoBuildJob {
    /** How a run stands. Ids are part of the wire format: only append. */
    public enum State {
        RUNNING, WAITING, PAUSED, FINISHED, STOPPED;

        public boolean over() {
            return this == FINISHED || this == STOPPED;
        }
    }

    /** What happened in one tick that the owner should hear about. */
    public enum Event {
        NONE,
        /** Paused itself: an item ran out, or the owner left. See {@link #message()}. */
        PAUSED,
        /** Everything is done: ding. */
        FINISHED,
        /** Stopped itself: the dimension is gone. */
        STOPPED
    }

    /** Where the items come from: the owner's linked chests. */
    public interface Supplies {
        /** How many of an item the chests hold. */
        long count(String item);

        /** Takes up to {@code count} of an item out of the chests; returns how many came out. */
        int take(String item, int count);
    }

    /** Most positions looked at in one tick (already-correct ones cost no placing time, but still a look). */
    static final int MAX_LOOKS_PER_TICK = 512;

    private final UUID id;
    private final UUID owner;
    private final String hash;
    private final String name;
    private final String dimension;
    private final List<AutoBuildPlan.Step> steps;
    private final double perTick;

    private State state = State.RUNNING;
    private String message = "";
    private double credit;
    private int index;
    private Integer layer;
    private final List<AutoBuildPlan.Step> deferred = new ArrayList<>();
    private final ArrayDeque<AutoBuildPlan.Step> retry = new ArrayDeque<>();
    private long done, placed, already, wrong, skipped;
    private long version;

    /**
     * @param blocksPerSecond placing speed; clamped to 1..1000
     */
    public AutoBuildJob(UUID id, UUID owner, String hash, String name, String dimension, List<AutoBuildPlan.Step> steps, int blocksPerSecond) {
        this.id = id;
        this.owner = owner;
        this.hash = hash;
        this.name = name;
        this.dimension = dimension;
        this.steps = List.copyOf(steps);
        this.perTick = Math.max(1, Math.min(1000, blocksPerSecond)) / 20.0;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String hash() {
        return hash;
    }

    public String name() {
        return name;
    }

    public String dimension() {
        return dimension;
    }

    public State state() {
        return state;
    }

    /** Why it waits, paused or stopped; empty while it runs. */
    public String message() {
        return message;
    }

    /** Steps dealt with (placed, already right, or skipped). */
    public long done() {
        return done;
    }

    /** Every step (a door or bed counts once). */
    public long total() {
        return steps.size();
    }

    public long placed() {
        return placed;
    }

    /** Positions that were already right. */
    public long alreadyThere() {
        return already;
    }

    /** Positions left alone: a different block there, no support, or a block AutoBuild doesn't place. */
    public long skipped() {
        return wrong + skipped;
    }

    /** Of {@link #skipped()}, those with a different block in the way. */
    public long wrongBlocks() {
        return wrong;
    }

    /** Bumps whenever the state, message or counts change. */
    public long version() {
        return version;
    }

    // ---- control ----------------------------------------------------------------------------------------------------

    public void pause(String why) {
        if (state.over() || state == State.PAUSED) return;
        state = State.PAUSED;
        message = why == null ? "" : why;
        version++;
    }

    public void resume() {
        if (state != State.PAUSED) return;
        state = State.RUNNING;
        message = "";
        credit = 0;
        version++;
    }

    public void stop(String why) {
        if (state.over()) return;
        state = State.STOPPED;
        message = why == null ? "" : why;
        version++;
    }

    /** The line the player sees when it ends: "AutoBuild finished: Castle (1,200 placed, 3 skipped)". */
    public String summary() {
        String counts = String.format(Locale.ROOT, "%,d placed, %,d skipped", placed, skipped());
        return (state == State.FINISHED ? "AutoBuild finished: " : "AutoBuild stopped: ") + name + " (" + counts + ")";
    }

    // ---- running ----------------------------------------------------------------------------------------------------

    private enum Outcome { PLACED, ALREADY, WRONG, SKIPPED, DEFER, OUT }

    /** One server tick: places up to this tick's share of blocks. */
    public Event tick(BuildWorld world, Supplies supplies) {
        if (state.over() || state == State.PAUSED) return Event.NONE;
        if (!world.dimensionExists(dimension)) {
            stop("The dimension is gone");
            return Event.STOPPED;
        }
        if (!world.isOnline(owner)) {
            pause("Paused while you are away");
            return Event.PAUSED;
        }
        boolean creative = world.isCreative(owner);
        credit = Math.min(credit + perTick, Math.max(1, perTick));
        int looks = 0;
        while (credit >= 1 && looks < MAX_LOOKS_PER_TICK) {
            boolean fromRetry = !retry.isEmpty();
            AutoBuildPlan.Step s = peek();
            if (s == null) {
                state = State.FINISHED;
                message = "";
                version++;
                return Event.FINISHED;
            }
            fromRetry = fromRetry || !retry.isEmpty();
            looks++;
            if (!loaded(world, s)) {
                if (state != State.WAITING) {
                    state = State.WAITING;
                    message = "Waiting for chunks to load";
                    version++;
                }
                return Event.NONE;
            }
            if (state == State.WAITING) {
                state = State.RUNNING;
                message = "";
                version++;
            }
            Outcome o = attempt(s, world, supplies, creative, fromRetry);
            switch (o) {
                case PLACED -> {
                    placed++;
                    credit -= 1;
                }
                case ALREADY -> already++;
                case WRONG -> wrong++;
                case SKIPPED -> skipped++;
                case DEFER -> deferred.add(s);
                case OUT -> {
                    return Event.PAUSED;
                }
            }
            if (o != Outcome.DEFER) done++;
            advance();
            version++;
        }
        return Event.NONE;
    }

    /** The next step: the rest of the layer, then the layer's deferred steps once more, then the next layer. */
    private AutoBuildPlan.Step peek() {
        if (!retry.isEmpty()) return retry.peek();
        if (index < steps.size() && layer != null && steps.get(index).order() == layer) return steps.get(index);
        if (!deferred.isEmpty()) {
            retry.addAll(deferred);
            deferred.clear();
            return retry.peek();
        }
        if (index >= steps.size()) return null;
        layer = steps.get(index).order();
        return steps.get(index);
    }

    private void advance() {
        if (!retry.isEmpty()) retry.poll();
        else index++;
    }

    private boolean loaded(BuildWorld world, AutoBuildPlan.Step s) {
        for (AutoBuildPlan.Cell c : s.cells()) if (!world.isLoaded(dimension, c.x(), c.y(), c.z())) return false;
        return true;
    }

    private Outcome attempt(AutoBuildPlan.Step s, BuildWorld world, Supplies supplies, boolean creative, boolean fromRetry) {
        AutoBuildPlan.Cell main = s.main();
        BlockState here = world.get(dimension, main.x(), main.y(), main.z());
        Compare.Result r = Compare.classify(main.state(), here);
        if (r == Compare.Result.CORRECT) return Outcome.ALREADY;
        if (r != Compare.Result.MISSING) return Outcome.WRONG;
        // The other half's spot must be free (or already right) too: never half a door, never a second door.
        List<AutoBuildPlan.Cell> partnersToPlace = new ArrayList<>();
        for (AutoBuildPlan.Cell p : s.partners()) {
            Compare.Result pr = Compare.classify(p.state(), world.get(dimension, p.x(), p.y(), p.z()));
            if (pr == Compare.Result.MISSING) partnersToPlace.add(p);
            else if (pr != Compare.Result.CORRECT) return Outcome.WRONG;
        }
        if (s.kind() != AutoBuildPlan.Kind.NORMAL) return Outcome.SKIPPED;
        switch (world.check(dimension, main.x(), main.y(), main.z(), main.state())) {
            case UNKNOWN_BLOCK -> {
                return Outcome.SKIPPED;
            }
            case UNSUPPORTED -> {
                return fromRetry ? Outcome.SKIPPED : Outcome.DEFER;
            }
            case OK -> {
            }
        }
        if (!creative) {
            for (Map.Entry<String, Integer> e : s.items().entrySet()) {
                if (supplies.count(e.getKey()) < e.getValue()) {
                    pause("Out of " + Items.pretty(e.getKey()).toLowerCase(Locale.ROOT) + ": put more in a linked chest, then resume");
                    return Outcome.OUT;
                }
            }
            for (Map.Entry<String, Integer> e : s.items().entrySet()) {
                int got = supplies.take(e.getKey(), e.getValue());
                if (got < e.getValue()) {
                    pause("Out of " + Items.pretty(e.getKey()).toLowerCase(Locale.ROOT) + ": put more in a linked chest, then resume");
                    return Outcome.OUT;
                }
            }
        }
        if (!world.place(dimension, main.x(), main.y(), main.z(), main.state())) return Outcome.SKIPPED;
        for (AutoBuildPlan.Cell p : partnersToPlace) world.place(dimension, p.x(), p.y(), p.z(), p.state());
        return Outcome.PLACED;
    }
}
