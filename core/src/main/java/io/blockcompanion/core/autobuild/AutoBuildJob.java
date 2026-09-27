package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.model.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One AutoBuild run: walks the {@link AutoBuildPlan} steps in the order its {@link AutoBuildOptions} pick and places
 * them at a steady rate, taking each block's items out of the owner's linked chests as it goes (nothing in creative).
 * Driven by {@link #tick} once per server tick; Minecraft-free, so the mods and the Paper plugin run the same one.
 *
 * <ul>
 *     <li>A position that is already right is passed over. One with a different block is left alone and counted as
 *     skipped, unless the replace mode may break that kind of block: then it is broken (its drops go to the linked
 *     chests, or on the ground when they are full) and the schematic's block goes in its place.</li>
 *     <li>With air cleared ({@link AutoBuildOptions#clearsAir}), blocks where the schematic has air are broken the same
 *     way.</li>
 *     <li>A block whose support isn't there yet goes to the end of its group (its layer, or its kind of block) and is
 *     tried once more there. Bottom up, still unsupported (sand over a hole, a torch with no wall) it is skipped; in
 *     the other orders it waits for one last bottom-up pass at the end, and is skipped only if it fails there too.</li>
 *     <li>With a radius, only blocks that near the owner are built; the rest wait ({@link State#WAITING}) until the
 *     owner comes closer.</li>
 *     <li>A step whose chunk isn't loaded waits ({@link State#WAITING}) until it is.</li>
 *     <li>When the chests run out of an item partway (someone took it), the run pauses and says what is missing, or
 *     with {@link AutoBuildOptions#skipMissing} skips that block and carries on.</li>
 * </ul>
 *
 * The options can change while it runs ({@link #setOptions}): the steps not done yet are put in the new order, and
 * blocks skipped as in the way or without items get another go.
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

    /** Where the items come from (the owner's linked chests), and where broken blocks' drops go. */
    public interface Supplies {
        /** How many of an item the chests hold. */
        long count(String item);

        /** Takes up to {@code count} of an item out of the chests; returns how many came out. */
        int take(String item, int count);

        /** Where drops of broken blocks go; null: nowhere (they are lost). */
        default BuildWorld.Drops drops() {
            return null;
        }
    }

    /** Most positions looked at in one tick (already-correct ones cost no placing time, but still a look). */
    static final int MAX_LOOKS_PER_TICK = 512;
    /** Most positions outside the radius passed over in one tick (a cheap distance check each). */
    static final int MAX_PASSES_PER_TICK = 16_384;
    /** Ticks between two looks for blocks near the owner while none are. */
    static final int RANGE_WAIT_TICKS = 20;
    /** Ticks between two re-sorts of the nearest-first order (only when the owner moved). */
    static final int NEAREST_SORT_TICKS = 20;

    private final UUID id;
    private final UUID owner;
    private final String hash;
    private final String name;
    private final String dimension;
    /** Every step planned, in plan order. */
    private final List<AutoBuildPlan.Step> all;
    private AutoBuildOptions options;
    private double perTick;

    private State state = State.RUNNING;
    private String message = "";
    private double credit;

    // The current pass: steps in order, with where it is.
    private List<AutoBuildPlan.Step> queue = new ArrayList<>();
    private int index;
    private Object group;
    /** A pass over the steps that failed for want of support in a non-bottom-up order: bottom up, and the last try. */
    private boolean latePass;
    /** Some step of this pass was within the radius. */
    private boolean passFound;
    private final List<AutoBuildPlan.Step> deferred = new ArrayList<>();
    private final ArrayDeque<AutoBuildPlan.Step> retry = new ArrayDeque<>();
    /** Unsupported twice in a non-bottom-up order: tried again bottom up at the end. */
    private List<AutoBuildPlan.Step> late = new ArrayList<>();
    /** Outside the radius this pass. */
    private List<AutoBuildPlan.Step> away = new ArrayList<>();
    private int rangeWait;
    private int sortTicks;
    private double[] sortedAt;

    private final Set<AutoBuildPlan.Step> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AutoBuildPlan.Step> wrongSteps = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AutoBuildPlan.Step> noItemSteps = Collections.newSetFromMap(new IdentityHashMap<>());
    private long total, done, placed, already, skipped, removed;
    private long version;

    /**
     * @param blocksPerSecond placing speed; clamped to 1..1000
     */
    public AutoBuildJob(UUID id, UUID owner, String hash, String name, String dimension, List<AutoBuildPlan.Step> steps, int blocksPerSecond) {
        this(id, owner, hash, name, dimension, steps, AutoBuildOptions.ofRate(blocksPerSecond));
    }

    public AutoBuildJob(UUID id, UUID owner, String hash, String name, String dimension, List<AutoBuildPlan.Step> steps, AutoBuildOptions options) {
        this.id = id;
        this.owner = owner;
        this.hash = hash;
        this.name = name;
        this.dimension = dimension;
        this.all = new ArrayList<>(steps);
        this.options = options;
        this.perTick = options.blocksPerSecond() / 20.0;
        rebuild(null);
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

    public AutoBuildOptions options() {
        return options;
    }

    /** True when some planned step clears air (air was planned when it started or since). */
    public boolean hasClearSteps() {
        for (AutoBuildPlan.Step s : all) if (s.kind() == AutoBuildPlan.Kind.CLEAR) return true;
        return false;
    }

    /** Why it waits, paused or stopped; empty while it runs. */
    public String message() {
        return message;
    }

    /** Steps dealt with (placed, already right, cleared or skipped). */
    public long done() {
        return done;
    }

    /** Every step the options build (a door or bed counts once). */
    public long total() {
        return total;
    }

    public long placed() {
        return placed;
    }

    /** Blocks broken: replaced by the schematic's block, or cleared where it has air. */
    public long removed() {
        return removed;
    }

    /** Positions that were already right. */
    public long alreadyThere() {
        return already;
    }

    /** Positions left alone: a different block there, no support, no items (skip missing), or a block AutoBuild doesn't place. */
    public long skipped() {
        return wrongSteps.size() + noItemSteps.size() + skipped;
    }

    /** Of {@link #skipped()}, those with a different block in the way. */
    public long wrongBlocks() {
        return wrongSteps.size();
    }

    /** Of {@link #skipped()}, those the chests had no items for (with skip missing on). */
    public long missingItems() {
        return noItemSteps.size();
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

    /**
     * New options while it runs. The speed applies at once; anything else puts the steps not done yet in order again,
     * and gives blocks skipped as in the way or without items another go. {@code airSteps} are {@link AutoBuildPlan.Kind#CLEAR}
     * steps to add (when clearing air was switched on and none were planned); may be empty.
     */
    public void setOptions(AutoBuildOptions o, List<AutoBuildPlan.Step> airSteps) {
        if (state.over()) return;
        AutoBuildOptions was = options;
        options = o;
        perTick = o.blocksPerSecond() / 20.0;
        if (airSteps != null) all.addAll(airSteps);
        if (!o.withRate(was.blocksPerSecond()).equals(was) || (airSteps != null && !airSteps.isEmpty())) rebuild(was);
        if (state == State.WAITING) {
            state = State.RUNNING;
            message = "";
        }
        version++;
    }

    /** The line the player sees when it ends: "AutoBuild finished: Castle (1,200 placed, 3 skipped)". */
    public String summary() {
        String counts = String.format(Locale.ROOT, "%,d placed, %,d skipped", placed, skipped());
        if (removed > 0) counts += String.format(Locale.ROOT, ", %,d removed", removed);
        return (state == State.FINISHED ? "AutoBuild finished: " : "AutoBuild stopped: ") + name + " (" + counts + ")";
    }

    // ---- order ------------------------------------------------------------------------------------------------------

    /** Puts every step the options build that isn't done yet in order, from the start. */
    private void rebuild(AutoBuildOptions was) {
        if (was != null) {
            // Blocks skipped because something was in the way or items were short get another go.
            handled.removeAll(wrongSteps);
            handled.removeAll(noItemSteps);
            wrongSteps.clear();
            noItemSteps.clear();
        }
        List<AutoBuildPlan.Step> rest = new ArrayList<>();
        long inScope = 0, doneInScope = 0;
        for (AutoBuildPlan.Step s : all) {
            if (!AutoBuildPlan.inScope(s, options)) continue;
            inScope++;
            if (handled.contains(s)) doneInScope++;
            else rest.add(s);
        }
        total = inScope;
        done = doneInScope;
        rest.sort(comparator(options.order(), rest, null));
        queue = rest;
        index = 0;
        group = null;
        latePass = false;
        passFound = false;
        deferred.clear();
        retry.clear();
        late = new ArrayList<>();
        away = new ArrayList<>();
        rangeWait = 0;
        sortedAt = null;
    }

    /** Top down: highest layer first; within a layer as bottom up. */
    private static final Comparator<AutoBuildPlan.Step> TOP_DOWN = Comparator.<AutoBuildPlan.Step>comparingInt(s -> -s.order())
            .thenComparing(s -> s.kind() != AutoBuildPlan.Kind.CLEAR)
            .thenComparing(AutoBuildPlan.Step::attached)
            .thenComparingInt(s -> s.main().z())
            .thenComparingInt(s -> s.main().x());

    private static Comparator<AutoBuildPlan.Step> comparator(AutoBuildOptions.Order order, List<AutoBuildPlan.Step> steps, double[] pos) {
        return switch (order) {
            case BOTTOM_UP -> AutoBuildPlan.ORDER;
            case TOP_DOWN -> TOP_DOWN;
            case BY_BLOCK -> {
                // Kinds of block in the order their lowest block comes (foundations first), clearing before any.
                Map<String, Integer> lowest = new HashMap<>();
                for (AutoBuildPlan.Step s : steps) lowest.merge(kindOf(s), s.order(), Math::min);
                yield Comparator.<AutoBuildPlan.Step>comparingInt(s -> s.kind() == AutoBuildPlan.Kind.CLEAR ? 0 : 1)
                        .thenComparingInt(s -> lowest.get(kindOf(s)))
                        .thenComparing(AutoBuildJob::kindOf)
                        .thenComparing(AutoBuildPlan.ORDER);
            }
            case NEAREST -> pos == null ? AutoBuildPlan.ORDER
                    : Comparator.<AutoBuildPlan.Step>comparingDouble(s -> distanceSq(s, pos)).thenComparing(AutoBuildPlan.ORDER);
        };
    }

    private static String kindOf(AutoBuildPlan.Step s) {
        return s.main().state().name();
    }

    /** Steps of one group wait for each other: a deferred step is tried again once its group is through. */
    private Object groupOf(AutoBuildPlan.Step s) {
        if (latePass) return s.order();
        return switch (options.order()) {
            case BOTTOM_UP, TOP_DOWN -> s.order();
            case BY_BLOCK -> kindOf(s);
            case NEAREST -> Boolean.TRUE;
        };
    }

    private static double distanceSq(AutoBuildPlan.Step s, double[] pos) {
        double dx = s.main().x() + 0.5 - pos[0], dy = s.main().y() + 0.5 - pos[1], dz = s.main().z() + 0.5 - pos[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean inRange(AutoBuildPlan.Step s, double[] pos) {
        if (options.radius() <= 0) return true;
        if (pos == null) return false;
        double r = options.radius() + 0.5;
        return distanceSq(s, pos) <= r * r;
    }

    // ---- running ----------------------------------------------------------------------------------------------------

    private enum Outcome { PLACED, CLEARED, ALREADY, WRONG, SKIPPED, NO_ITEMS, DEFER, OUT }

    /** Why {@link #peek} found no step to try now. */
    private enum Stall { NONE, BUSY, AWAY }

    private Stall stall = Stall.NONE;

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
        if (rangeWait > 0) {
            rangeWait--;
            return Event.NONE;
        }
        boolean creative = world.isCreative(owner);
        double[] pos = options.radius() > 0 || options.order() == AutoBuildOptions.Order.NEAREST ? world.position(owner, dimension) : null;
        if (options.order() == AutoBuildOptions.Order.NEAREST && !latePass) resortNearest(pos);
        credit = Math.min(credit + perTick, Math.max(1, perTick));
        int looks = 0;
        passBudget = MAX_PASSES_PER_TICK;
        while (credit >= 1 && looks < MAX_LOOKS_PER_TICK) {
            boolean fromRetry = !retry.isEmpty();
            AutoBuildPlan.Step s = peek(pos);
            if (s == null) {
                if (stall == Stall.BUSY) return Event.NONE;
                if (stall == Stall.AWAY) {
                    String why = String.format(Locale.ROOT, "Nothing left within %d blocks of you: come closer", options.radius());
                    if (state != State.WAITING || !message.equals(why)) {
                        state = State.WAITING;
                        message = why;
                        version++;
                    }
                    rangeWait = RANGE_WAIT_TICKS;
                    return Event.NONE;
                }
                state = State.FINISHED;
                message = "";
                version++;
                return Event.FINISHED;
            }
            fromRetry = fromRetry || !retry.isEmpty();
            looks++;
            if (!loaded(world, s)) {
                if (state != State.WAITING || !message.equals("Waiting for chunks to load")) {
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
                case CLEARED -> credit -= 1;
                case ALREADY -> already++;
                case WRONG -> wrongSteps.add(s);
                case SKIPPED -> skipped++;
                case NO_ITEMS -> noItemSteps.add(s);
                case DEFER -> {
                    if (fromRetry) late.add(s);
                    else deferred.add(s);
                }
                case OUT -> {
                    return Event.PAUSED;
                }
            }
            if (o != Outcome.DEFER) {
                handled.add(s);
                done++;
            }
            advance();
            version++;
        }
        return Event.NONE;
    }

    /** Steps outside the radius that may still be passed over this tick. */
    private int passBudget;

    /**
     * The next step to try: the rest of the group, then the group's deferred steps once more, then the next group;
     * after the pass, the late steps bottom up, then another pass over the steps that were out of range. Null when
     * there is none now: {@link #stall} says whether it is finished, out of range or out of time this tick.
     */
    private AutoBuildPlan.Step peek(double[] pos) {
        stall = Stall.NONE;
        while (true) {
            if (!retry.isEmpty()) return retry.peek();
            if (index < queue.size()) {
                AutoBuildPlan.Step s = queue.get(index);
                Object g = groupOf(s);
                if (group != null && !g.equals(group) && !deferred.isEmpty()) {
                    retry.addAll(deferred);
                    deferred.clear();
                    continue;
                }
                if (!inRange(s, pos)) {
                    away.add(s);
                    index++;
                    if (--passBudget <= 0) {
                        stall = Stall.BUSY;
                        return null;
                    }
                    continue;
                }
                group = g;
                passFound = true;
                return s;
            }
            if (!deferred.isEmpty()) {
                retry.addAll(deferred);
                deferred.clear();
                continue;
            }
            // This pass is through.
            group = null;
            if (!latePass && !late.isEmpty()) {
                // What wanted support below in another order: once more, bottom up.
                List<AutoBuildPlan.Step> l = late;
                late = new ArrayList<>();
                l.sort(AutoBuildPlan.ORDER);
                queue = l;
                index = 0;
                latePass = true;
                continue;
            }
            latePass = false;
            if (away.isEmpty()) return null;
            List<AutoBuildPlan.Step> next = away;
            away = new ArrayList<>();
            next.sort(comparator(options.order(), next, pos));
            queue = next;
            index = 0;
            boolean found = passFound;
            passFound = false;
            if (!found) {
                stall = Stall.AWAY;
                return null;
            }
        }
    }

    private void advance() {
        if (!retry.isEmpty()) retry.poll();
        else index++;
    }

    /** Nearest first: puts what is left of this pass in order of distance again when the owner has moved. */
    private void resortNearest(double[] pos) {
        if (pos == null) return;
        if (sortedAt != null && ++sortTicks < NEAREST_SORT_TICKS) return;
        sortTicks = 0;
        if (sortedAt != null) {
            double dx = pos[0] - sortedAt[0], dy = pos[1] - sortedAt[1], dz = pos[2] - sortedAt[2];
            if (dx * dx + dy * dy + dz * dz < 1) return;
        }
        sortedAt = pos.clone();
        if (index < queue.size()) queue.subList(index, queue.size()).sort(comparator(AutoBuildOptions.Order.NEAREST, List.of(), pos));
    }

    private boolean loaded(BuildWorld world, AutoBuildPlan.Step s) {
        for (AutoBuildPlan.Cell c : s.cells()) if (!world.isLoaded(dimension, c.x(), c.y(), c.z())) return false;
        return true;
    }

    /** Whether the replace mode may break what stands at the cell. */
    private boolean mayBreak(BuildWorld world, AutoBuildPlan.Cell c) {
        return options.replace().breaks() && options.mayBreak(world.removal(dimension, c.x(), c.y(), c.z()));
    }

    private Outcome attempt(AutoBuildPlan.Step s, BuildWorld world, Supplies supplies, boolean creative, boolean fromRetry) {
        AutoBuildPlan.Cell main = s.main();
        BlockState here = world.get(dimension, main.x(), main.y(), main.z());
        BuildWorld.Drops drops = creative ? null : supplies.drops();
        if (s.kind() == AutoBuildPlan.Kind.CLEAR) {
            if (Compare.classify(BlockState.AIR, here) != Compare.Result.EXTRA) return Outcome.ALREADY;
            if (!options.clearsAir() || !mayBreak(world, main)) return Outcome.WRONG;
            if (!world.replace(dimension, main.x(), main.y(), main.z(), BlockState.AIR, drops)) return Outcome.SKIPPED;
            removed++;
            return Outcome.CLEARED;
        }
        Compare.Result r = Compare.classify(main.state(), here);
        if (r == Compare.Result.CORRECT) return Outcome.ALREADY;
        boolean replaceMain = false;
        if (r != Compare.Result.MISSING) {
            if (!mayBreak(world, main)) return Outcome.WRONG;
            replaceMain = true;
        }
        // The other half's spot must be free (or already right, or breakable) too: never half a door, never a second door.
        List<AutoBuildPlan.Cell> partnersToPlace = new ArrayList<>();
        List<AutoBuildPlan.Cell> partnersToReplace = new ArrayList<>();
        for (AutoBuildPlan.Cell p : s.partners()) {
            Compare.Result pr = Compare.classify(p.state(), world.get(dimension, p.x(), p.y(), p.z()));
            if (pr == Compare.Result.MISSING) partnersToPlace.add(p);
            else if (pr == Compare.Result.CORRECT) continue;
            else if (mayBreak(world, p)) partnersToReplace.add(p);
            else return Outcome.WRONG;
        }
        if (s.kind() != AutoBuildPlan.Kind.NORMAL) return Outcome.SKIPPED;
        switch (world.check(dimension, main.x(), main.y(), main.z(), main.state())) {
            case UNKNOWN_BLOCK -> {
                return Outcome.SKIPPED;
            }
            case UNSUPPORTED -> {
                // Bottom up (and in the last pass) the support had its chance; in other orders it may still come.
                boolean last = latePass || options.order() == AutoBuildOptions.Order.BOTTOM_UP;
                return fromRetry && last ? Outcome.SKIPPED : Outcome.DEFER;
            }
            case OK -> {
            }
        }
        if (!creative) {
            for (Map.Entry<String, Integer> e : s.items().entrySet()) {
                if (supplies.count(e.getKey()) < e.getValue()) return outOf(e.getKey());
            }
            for (Map.Entry<String, Integer> e : s.items().entrySet()) {
                int got = supplies.take(e.getKey(), e.getValue());
                if (got < e.getValue()) return outOf(e.getKey());
            }
        }
        boolean ok = replaceMain ? world.replace(dimension, main.x(), main.y(), main.z(), main.state(), drops)
                : world.place(dimension, main.x(), main.y(), main.z(), main.state());
        if (!ok) return Outcome.SKIPPED;
        if (replaceMain) removed++;
        for (AutoBuildPlan.Cell p : partnersToPlace) world.place(dimension, p.x(), p.y(), p.z(), p.state());
        for (AutoBuildPlan.Cell p : partnersToReplace) {
            if (world.replace(dimension, p.x(), p.y(), p.z(), p.state(), drops)) removed++;
        }
        return Outcome.PLACED;
    }

    /** The chests lack an item: skip the block (skip missing), or pause and say what is missing. */
    private Outcome outOf(String item) {
        if (options.skipMissing()) return Outcome.NO_ITEMS;
        pause("Out of " + Items.pretty(item).toLowerCase(Locale.ROOT) + ": put more in a linked chest, then resume");
        return Outcome.OUT;
    }
}
