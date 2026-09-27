package io.blockcompanion.client.autobuild;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.core.autobuild.AutoBuildJob;
import io.blockcompanion.core.autobuild.AutoBuildPlan;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.progress.ProgressTracker;
import io.blockcompanion.core.sync.Features;
import io.blockcompanion.core.sync.Message;
import io.blockcompanion.core.sync.PlacementPose;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The client side of AutoBuild (the Resources step of the B screen): whether the Start button may be pressed and why
 * not, starting, pausing, resuming and stopping, and the progress line. The server does the building (and checks
 * everything again); see {@link io.blockcompanion.core.autobuild.AutoBuildJob}. Main thread only.
 */
public final class AutoBuildClient {
    /** What the Start button shows: whether it may be pressed, its tooltip, and how many blocks it would place. */
    public record Readiness(boolean canStart, String tip, long blocks) {
    }

    private AutoBuildClient() {
    }

    // The plan of the last placement asked about, and the answer, until something it depends on changes.
    private static ProgressTracker planTracker;
    private static List<AutoBuildPlan.Step> plan = List.of();
    private static long seenTracker = -1, seenChests = -1;
    private static boolean seenCreative;
    private static Readiness cached;

    private static SyncClient sync() {
        return ClientSync.client();
    }

    /** Where the placement is, as the server sees placements. */
    public static PlacementPose pose(LoadedPlacement lp) {
        BlockPos o = lp.placement.origin();
        return new PlacementPose(lp.dimension, o.x(), o.y(), o.z(), lp.placement.rotation(), lp.placement.mirrored());
    }

    /** The latest AutoBuild news for the placement, or null. */
    public static Message.AutoBuildStatus status(LoadedPlacement lp) {
        SyncClient s = sync();
        return s == null || lp == null ? null : s.autoBuild(pose(lp));
    }

    /** True while an AutoBuild of the placement is under way (running, waiting or paused). */
    public static boolean active(LoadedPlacement lp) {
        Message.AutoBuildStatus st = status(lp);
        return st != null && !st.state().over();
    }

    /** Whether AutoBuild can start on the placement now, and the button's tooltip. */
    public static Readiness check(LoadedPlacement lp) {
        Minecraft mc = Minecraft.getInstance();
        SyncClient s = sync();
        if (s == null || !s.serverPresent()) {
            return no("AutoBuild runs on the server: it needs BlockCompanion there (singleplayer always has it).");
        }
        Features f = s.features();
        if (!f.autoBuildAllowed()) return no("This server has AutoBuild turned off.");
        if (!s.autoBuildAllowed()) return no("You may not use AutoBuild on this server.");
        if (lp == null) return no("Load a schematic first (step 1).");
        if (mc.level == null || mc.player == null) return no("Not in a world.");
        if (!BlockCompanionClient.here(lp)) return no("Go to the placement's dimension first.");
        if (s.autoBuildStarting(pose(lp))) return no("Sending the schematic to the server...");
        if (active(lp)) return no("AutoBuild is already building this placement.");
        ProgressTracker t = lp.progress().tracker();
        if (t == null) return no("Counting...");
        boolean creative = mc.player.isCreative();
        long chestsVersion = ChestTracker.get().chests().version();
        if (t != planTracker) {
            planTracker = t;
            plan = AutoBuildPlan.plan(t.placement());
            seenTracker = -1;
        }
        if (cached != null && t.version() == seenTracker && chestsVersion == seenChests && creative == seenCreative) return cached;
        seenTracker = t.version();
        seenChests = chestsVersion;
        seenCreative = creative;
        cached = work(t, creative);
        return cached;
    }

    private static Readiness no(String why) {
        return new Readiness(false, why, 0);
    }

    private static Readiness work(ProgressTracker t, boolean creative) {
        // Still to place: what the progress tracker has as missing or not seen yet. Wrong blocks are left alone.
        java.util.function.Predicate<AutoBuildPlan.Step> toPlace = st -> {
            ProgressTracker.Status status = t.status(st.main().x(), st.main().y(), st.main().z());
            return status == ProgressTracker.Status.MISSING || status == ProgressTracker.Status.UNKNOWN;
        };
        long blocks = AutoBuildPlan.count(plan, toPlace);
        if (blocks == 0) return no("Nothing left to place.");
        String what = String.format(Locale.ROOT, "%,d %s", blocks, blocks == 1 ? "block" : "blocks");
        if (creative) {
            return new Readiness(true, "Creative: no materials needed. Places " + what + " layer by layer from the bottom and dings when done.",
                    blocks);
        }
        if (ChestTracker.get().chests().size() == 0) return no("Link chests with the materials first (Ctrl+right-click them with the tool).");
        Map<String, Long> shortfall = AutoBuildPlan.shortfall(AutoBuildPlan.required(plan, toPlace), ChestTracker.get().chests().totals());
        if (!shortfall.isEmpty()) return no(AutoBuildPlan.describeShort(shortfall, 4));
        return new Readiness(true, "Places " + what + " from your linked chests, layer by layer from the bottom, one item per block. "
                + "Dings when done.", blocks);
    }

    /** Starts AutoBuild on the placement (uploading its schematic first if the server lacks it). */
    public static void start(LoadedPlacement lp) {
        SyncClient s = sync();
        if (s == null || lp == null) return;
        if (s.startAutoBuild(lp.name(), pose(lp), BlockCompanionClient.config().autoBuildSpeed, ChestTracker.get().chests().all())) {
            BlockCompanionClient.actionBar("AutoBuild: starting " + lp.shortName());
        }
        cached = null;
    }

    public static void control(LoadedPlacement lp, Message.AutoBuildAction action) {
        Message.AutoBuildStatus st = status(lp);
        SyncClient s = sync();
        if (st != null && s != null) s.controlAutoBuild(st.job(), action);
    }

    /** "AutoBuild: 340 / 1,200", with the state when it isn't simply running. */
    public static String line(Message.AutoBuildStatus st) {
        String base = String.format(Locale.ROOT, "AutoBuild: %,d / %,d", st.done(), st.total());
        return switch (st.state()) {
            case RUNNING -> base;
            case WAITING, PAUSED -> base + " (" + (st.message().isEmpty() ? label(st.state()) : st.message()) + ")";
            case FINISHED, STOPPED -> st.message().isEmpty() ? base + " (" + label(st.state()) + ")" : st.message();
        };
    }

    private static String label(AutoBuildJob.State state) {
        return switch (state) {
            case RUNNING -> "running";
            case WAITING -> "waiting";
            case PAUSED -> "paused";
            case FINISHED -> "finished";
            case STOPPED -> "stopped";
        };
    }

    /** News from the server: the progress goes on the action bar (a pause is said in the chat instead). */
    public static void statusArrived(Message.AutoBuildStatus st) {
        cached = null;
        if (st.state() != AutoBuildJob.State.PAUSED) BlockCompanionClient.actionBar(line(st));
    }
}
