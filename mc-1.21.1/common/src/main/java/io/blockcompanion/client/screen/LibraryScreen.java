package io.blockcompanion.client.screen;

import net.minecraft.Util;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.hud.Bars;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.progress.ProgressTracker;
import io.blockcompanion.core.project.BdProject;
import io.blockcompanion.client.autobuild.AutoBuildClient;
import io.blockcompanion.core.sync.Features;
import io.blockcompanion.core.sync.Message;
import io.blockcompanion.core.sync.Permission;
import io.blockcompanion.core.sync.SchematicInfo;
import io.blockcompanion.core.sync.SharedPlacement;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The schematic screen (key B), as four steps along the top, each usable on its own:
 * <ol>
 *     <li><b>Source</b>: the schematics on this computer (BlockDesigner projects and Sponge schematics first, green) or
 *     shared on the server (placements and files, with share, load, lock, delete and unlink); load one in front of
 *     you, or get the project open in BlockDesigner.</li>
 *     <li><b>Placement</b>: what is loaded in this world, with where it is, its progress and whether it is locked; lock
 *     presets, show / hide, bring here, turn, mirror, follow BlockDesigner and unload.</li>
 *     <li><b>Resources</b>: what the selected placement still needs, against your inventory and linked chests, and
 *     AutoBuild (the server builds it from the linked chests), with its Options screen.</li>
 *     <li><b>BlockDesigner</b>: the live link: off, waiting or connected, the linked project, Start / Stop, Get project
 *     and Send status.</li>
 * </ol>
 * The step and source last used are remembered while the game runs.
 */
public final class LibraryScreen extends Screen {
    /** The steps, in order. */
    public enum Step {
        SOURCE("Source"), PLACEMENT("Placement"), RESOURCES("Resources"), LINK("BlockDesigner");

        final String label;

        Step(String label) {
            this.label = label;
        }
    }

    /** The lock presets. */
    private enum LockPreset {
        NONE("Unlocked", Set.of()),
        POSITION("Position", EnumSet.of(PlacementLock.POSITION)),
        PLACE("In place", PlacementLock.IN_PLACE),
        ALL("Everything", PlacementLock.ALL),
        CUSTOM("Custom", Set.of());

        final String label;
        final Set<PlacementLock> locks;

        LockPreset(String label, Set<PlacementLock> locks) {
            this.label = label;
            this.locks = locks;
        }

        static LockPreset of(Set<PlacementLock> locks) {
            for (LockPreset p : values()) if (p != CUSTOM && p.locks.equals(locks)) return p;
            return CUSTOM;
        }

        String tooltip() {
            return switch (this) {
                case NONE -> "Nothing locked: scroll to move and turn it, M mirrors it.";
                case POSITION -> "It can't be moved (turning, mirroring and layers still work).";
                case PLACE -> "Locked in place: it can't be moved, turned or mirrored.";
                case ALL -> "Everything: in place, and the layer view can't change either.";
                case CUSTOM -> "Some parts locked.";
            };
        }
    }

    private static Step lastStep = Step.SOURCE;
    private static boolean lastServer;

    private final Screen parent;
    private Step step;
    /** The Source step shows the server's shared space instead of the local folder. */
    private boolean server;
    private int px, py, pw, ph;

    // Source, local
    private FileList files;
    private String error;
    private Button loadButton, fileUnloadButton, deleteButton;
    // Source, server
    private SharedList shared;
    private Button shareButton, sharedLoad, sharedLock, sharedDelete, sharedUnlink;
    // Placement
    private LoadedList loaded;
    private final List<Button> lockButtons = new ArrayList<>();
    private Button showButton, hereButton, turnButton, mirrorButton, unloadButton;
    private Button liveButton, editButton;
    /** The edit button's tooltip as last set: why it is off, or what it does. */
    private String editTip;
    private int detailX, detailW, editW;
    // Resources
    private ResourceList resources;
    private LoadedPlacement counted;
    private boolean visibleOnly;
    // AutoBuild, in the Resources step
    private Button autoStart, autoPause, autoStop, autoOptions;
    private String autoTip;
    /** The line next to the AutoBuild buttons and its colour, worked out each tick (not each frame). */
    private String autoLine = "";
    private int autoLineColor = Ui.MUTED;
    /** The linked chests line, and the chests version and tool it was made for. */
    private String chestLine;
    private long chestLineVersion = -1;
    private String chestLineTool;
    /** The grey line under each step's button, worked out each tick. */
    private String[] stepLines;
    // BlockDesigner
    private OptionList linkList;

    /** Layer lists of projects, read once per file version. */
    private final Map<String, String> projectInfo = new HashMap<>();

    public LibraryScreen(Screen parent) {
        this(parent, lastStep, lastServer);
    }

    public LibraryScreen(Screen parent, Step step, boolean server) {
        super(Component.translatable("blockcompanion.library.title"));
        this.parent = parent;
        this.step = step;
        this.server = server;
    }

    private static SyncClient sync() {
        return ClientSync.client();
    }

    private static boolean serverPresent() {
        SyncClient s = sync();
        return s != null && s.serverPresent();
    }

    // ---- layout -----------------------------------------------------------------------------------------------------

    private int stepBarY() {
        return Ui.TITLE_H + 4;
    }

    @Override
    protected void init() {
        files = null;
        shared = null;
        loaded = null;
        resources = null;
        autoStart = autoPause = autoStop = null;
        autoTip = null;
        autoLine = "";
        stepLines = null;
        chestLine = null;
        linkList = null;
        loadButton = fileUnloadButton = deleteButton = shareButton = sharedLoad = sharedLock = sharedDelete = sharedUnlink = null;
        showButton = hereButton = turnButton = mirrorButton = unloadButton = null;
        liveButton = editButton = null;
        editTip = null;
        lockButtons.clear();

        // The step bar.
        Step[] steps = Step.values();
        int barW = Math.min(width - 2 * Ui.PAD, 560);
        int barX = (width - barW) / 2, sw = (barW - (steps.length - 1) * Ui.GAP) / steps.length;
        for (int i = 0; i < steps.length; i++) {
            Step s = steps[i];
            Component label = Component.literal((i + 1) + "  ").withColor(s == step ? Ui.ACCENT : Ui.MUTED)
                    .append(Component.literal(sw < 70 && s == Step.LINK ? "BD" : s.label).withColor(s == step ? Ui.ACCENT : Ui.TEXT));
            Button b = addRenderableWidget(Button.builder(label, x -> go(s)).bounds(barX + i * (sw + Ui.GAP), stepBarY(), sw, 20).build());
            b.active = s != step;
        }

        pw = Math.min(width - 2 * Ui.PAD, 560);
        px = (width - pw) / 2;
        py = stepBarY() + 34;
        ph = height - 30 - py;

        switch (step) {
            case SOURCE -> {
                if (server) initShared();
                else initLocal();
            }
            case PLACEMENT -> initPlacement();
            case RESOURCES -> initResources();
            case LINK -> initLink();
        }

        // The footer: settings on the left, back / next / done on the right.
        int by = height - 25, bw = Math.min(80, (width - 2 * Ui.PAD - 3 * Ui.GAP) / 4);
        addRenderableWidget(Ui.button("Settings", "Every BlockCompanion option.", Ui.PAD, by, bw,
                b -> minecraft.setScreen(new SettingsScreen(this))));
        int rx = width - Ui.PAD - 3 * bw - 2 * Ui.GAP;
        Button back = addRenderableWidget(Ui.button("< Back", null, rx, by, bw, b -> go(steps[step.ordinal() - 1])));
        back.active = step.ordinal() > 0;
        Button next = addRenderableWidget(Ui.button("Next >", null, rx + bw + Ui.GAP, by, bw, b -> go(steps[step.ordinal() + 1])));
        next.active = step.ordinal() < steps.length - 1;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(rx + 2 * (bw + Ui.GAP), by, bw, 20).build());
        updateButtons();
    }

    private void go(Step s) {
        step = s;
        lastStep = s;
        rebuildWidgets();
    }

    private void setServer(boolean on) {
        server = on;
        lastServer = on;
        rebuildWidgets();
    }

    /** The two source buttons at the top of the Source step. */
    private void sourceSwitch() {
        int w = Math.min(100, (pw - 16) / 2);
        Button local = addRenderableWidget(Ui.button("This computer", "Schematics in your blockcompanion/schematics folder.", px + 6, py + 6, w,
                b -> setServer(false)));
        local.active = server;
        Button srv = addRenderableWidget(Ui.button("Server", serverPresent() ? "Schematics and placements shared on this server."
                : "Needs a server with BlockCompanion (mod or Paper plugin).", px + 6 + w + Ui.GAP, py + 6, w, b -> setServer(true)));
        srv.active = !server;
    }

    // ---- 1: source, this computer -----------------------------------------------------------------------------------

    private void initLocal() {
        sourceSwitch();
        int top = py + 30, bottom = py + ph - 52;
        files = new FileList(minecraft, pw - 6, bottom - top, top);
        files.setX(px + 3);
        addRenderableWidget(files);

        // Two rows of three: what to do with the selected file, then the folder and BlockDesigner.
        int n = 3, y = py + ph - 49, w = (pw - 12 - (n - 1) * Ui.GAP) / n, x = px + 6;
        loadButton = addRenderableWidget(Ui.button("Load", "Load the selected schematic in front of you (the others stay loaded).", x, y, w,
                b -> loadSelected()));
        fileUnloadButton = addRenderableWidget(Ui.button("Unload", "Removes every copy of the selected schematic from this world.",
                x + (w + Ui.GAP), y, w, b -> unloadSelected()));
        deleteButton = addRenderableWidget(Ui.button("Delete", "Deletes the selected file from the schematic folder (asks first).",
                x + 2 * (w + Ui.GAP), y, w, b -> deleteSelected()));
        y += 24;
        addRenderableWidget(Ui.button("Open folder", "Opens blockcompanion/schematics.", x, y, w,
                b -> Util.getPlatform().openFile(BlockCompanionClient.library().root().toFile())));
        addRenderableWidget(Ui.button("Refresh", "Reads the folder again.", x + (w + Ui.GAP), y, w, b -> refreshFiles()));
        List<String> apps = ClientLink.apps();
        Button grab = addRenderableWidget(Ui.button("From BlockDesigner", apps.isEmpty()
                ? "Asks BlockDesigner for the project it has open. Connect it first (step 4)."
                : "Asks " + String.join(", ", apps) + " for the project open in BlockDesigner.", x + 2 * (w + Ui.GAP), y, w, b -> {
            BlockCompanionClient.grab();
            onClose();
        }));
        grab.active = ClientLink.state() == ClientLink.State.CONNECTED;
        refreshFiles();
    }

    private void refreshFiles() {
        FileEntry before = files.getSelected();
        String keep = before == null ? null : before.entry.name();
        try {
            files.set(BlockCompanionClient.library().list());
            error = null;
        } catch (IOException e) {
            files.set(List.of());
            error = e.getMessage();
        }
        if (keep != null) {
            for (FileEntry e : files.children()) {
                if (e.entry.name().equals(keep)) files.setSelected(e);
            }
        }
        updateButtons();
    }

    private void loadSelected() {
        FileEntry e = files == null ? null : files.getSelected();
        if (e == null) return;
        if (BlockCompanionClient.load(e.entry.name())) go(Step.PLACEMENT);
    }

    /** The placements in this world loaded from a library file. */
    private static List<LoadedPlacement> loadedFrom(String name) {
        return BlockCompanionClient.placements().stream().filter(lp -> lp.name().equals(name)).toList();
    }

    private void unloadSelected() {
        FileEntry e = files == null ? null : files.getSelected();
        if (e == null) return;
        List<LoadedPlacement> copies = loadedFrom(e.entry.name());
        for (LoadedPlacement lp : copies) BlockCompanionClient.unload(lp, true);
        if (!copies.isEmpty()) BlockCompanionClient.actionBar("Unloaded " + copies.get(0).shortName()
                + (copies.size() > 1 ? " (" + copies.size() + " copies)" : ""));
        updateButtons();
    }

    private void deleteSelected() {
        FileEntry sel = files == null ? null : files.getSelected();
        if (sel == null) return;
        SchematicLibrary.Entry entry = sel.entry;
        int copies = loadedFrom(entry.name()).size();
        Component message = Component.literal("The file is removed from blockcompanion/schematics for good"
                + (copies > 0 ? ", and the " + (copies == 1 ? "copy" : copies + " copies") + " loaded in this world unloaded." : "."));
        minecraft.setScreen(new ConfirmScreen(ok -> {
            if (ok) {
                for (LoadedPlacement lp : loadedFrom(entry.name())) BlockCompanionClient.unload(lp, true);
                try {
                    Files.deleteIfExists(entry.path());
                    BlockCompanionClient.actionBar("Deleted " + entry.name());
                } catch (IOException ex) {
                    BlockCompanionClient.actionBar("Could not delete " + entry.name() + ": " + ex.getMessage());
                }
            }
            minecraft.setScreen(this);
        }, Component.literal("Delete " + entry.name() + "?"), message));
    }

    // ---- 1: source, server ------------------------------------------------------------------------------------------

    private void initShared() {
        sourceSwitch();
        int top = py + 42, bottom = py + ph - 28;
        shared = new SharedList(minecraft, pw - 6, bottom - top, top);
        shared.setX(px + 3);
        addRenderableWidget(shared);
        int n = 5, y = py + ph - 25, w = (pw - 12 - (n - 1) * Ui.GAP) / n, x = px + 6;
        shareButton = addRenderableWidget(Ui.button("Share mine", "Share the selected loaded schematic with everyone on the server.", x, y, w,
                b -> sync().shareCurrent()));
        sharedLoad = addRenderableWidget(Ui.button("Load", "Load the selected shared placement (and follow it) or schematic.",
                x + (w + Ui.GAP), y, w, b -> loadShared()));
        sharedLock = addRenderableWidget(Ui.button("Lock", "Lock or unlock the shared placement for everyone.", x + 2 * (w + Ui.GAP), y, w, b -> {
            if (shared.selected() instanceof SharedPlacement p) sync().setLocked(p.id(), !p.locked());
        }));
        sharedDelete = addRenderableWidget(Ui.button("Delete", "Remove it from the server.", x + 3 * (w + Ui.GAP), y, w, b -> {
            Object sel = shared.selected();
            if (sel instanceof SharedPlacement p) sync().deletePlacement(p.id());
            else if (sel instanceof SchematicInfo s) sync().deleteSchematic(s.hash());
            shared.setSelected(null);
        }));
        sharedUnlink = addRenderableWidget(Ui.button("Unlink", "Stop following the shared placement.", x + 4 * (w + Ui.GAP), y, w, b -> {
            sync().releaseEditLock();
            sync().unlink();
            refresh();
        }));
        refresh();
    }

    /** Rebuilds the shared list from the sync client (called when anything on the server changes). */
    public void refresh() {
        if (shared != null) shared.rebuild();
        updateButtons();
    }

    private void loadShared() {
        SyncClient s = sync();
        Object sel = shared.selected();
        if (sel instanceof SharedPlacement p) {
            s.load(p.id());
            go(Step.PLACEMENT);
        } else if (sel instanceof SchematicInfo i) {
            // A plain load: fetch the file into the library and put it in front of the player, not linked to anything.
            s.fetch(i.hash(), BlockCompanionClient::load);
            go(Step.PLACEMENT);
        }
    }

    private static String serverStatus(SyncClient s) {
        if (s == null || !s.serverPresent()) return Component.translatable("blockcompanion.shared.no_server").getString();
        Features f = s.features();
        if (!f.syncEnabled()) return Component.translatable("blockcompanion.shared.disabled").getString();
        String quota = f.playerQuota() < 0 ? "no quota" : size(f.playerUsed()) + " of " + size(f.playerQuota()) + " used";
        return s.serverSoftware() + " · " + quota + ", files up to " + size(f.maxFileSize());
    }

    // ---- 2: placement -----------------------------------------------------------------------------------------------

    private void initPlacement() {
        int listW = Math.max(120, (pw - 6) * 9 / 20);
        loaded = new LoadedList(minecraft, listW, ph - 6, py + 3);
        loaded.setX(px + 3);
        addRenderableWidget(loaded);
        loaded.set(BlockCompanionClient.placements());

        detailX = px + 3 + listW + 8;
        detailW = px + pw - 8 - detailX;
        // Edit in BlockDesigner: top right, beside the name.
        String edit = "Edit in BlockDesigner";
        editW = Math.min(font.width(edit) + 16, detailW / 2);
        editButton = addRenderableWidget(Ui.button(font.width(edit) + 8 <= editW ? edit : "Edit in BD", null, detailX + detailW - editW, py + 6,
                editW, b -> {
                    LoadedPlacement lp = selectedPlacement();
                    if (lp != null) BlockCompanionClient.editInBlockDesigner(lp);
                }));
        int lockY = py + 70, quarter = (detailW - 3 * Ui.GAP) / 4, third = (detailW - 2 * Ui.GAP) / 3;
        LockPreset[] presets = {LockPreset.NONE, LockPreset.POSITION, LockPreset.PLACE, LockPreset.ALL};
        String[] shortNames = {"None", "Move", "Place", "All"};
        for (int i = 0; i < presets.length; i++) {
            LockPreset p = presets[i];
            Button b = addRenderableWidget(Ui.button(quarter >= 58 ? p.label : shortNames[i], p.tooltip(), detailX + i * (quarter + Ui.GAP), lockY,
                    quarter, x -> setLocks(p)));
            lockButtons.add(b);
        }
        // Two rows of three: what to do with it. Below the locks when there is room, else against the bottom.
        int y = Math.max(lockY + 24, Math.min(lockY + 30, py + ph - 52));
        showButton = addRenderableWidget(Ui.button("Hide", "Show or hide its ghosts (its box still shows with the tool in hand).", detailX, y, third, b -> toggleShown()));
        hereButton = addRenderableWidget(Ui.button("Bring here", "Move it to just in front of you.", detailX + third + Ui.GAP, y, third, b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp != null) BlockCompanionClient.bringHere(lp);
        }));
        unloadButton = addRenderableWidget(Ui.button("Unload", "Removes it from this world.", detailX + 2 * (third + Ui.GAP), y, third, b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp == null) return;
            BlockCompanionClient.unload(lp, true);
            BlockCompanionClient.actionBar("Unloaded " + lp.shortName());
            loaded.set(BlockCompanionClient.placements());
            updateButtons();
        }));
        y += 24;
        turnButton = addRenderableWidget(Ui.button("Turn 90°", "Turns it a quarter clockwise.", detailX, y, third, b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp == null || lp.locked(PlacementLock.ROTATION)) return;
            lp.placement.rotate(1);
            BlockCompanionClient.changed(lp);
        }));
        mirrorButton = addRenderableWidget(Ui.button("Mirror", "Mirrors it.", detailX + third + Ui.GAP, y, third, b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp == null || lp.locked(PlacementLock.MIRROR)) return;
            lp.placement.toggleMirror();
            BlockCompanionClient.changed(lp);
        }));
        liveButton = addRenderableWidget(Ui.button("Live", "Follow BlockDesigner: reload this project when the app sends a new version.",
                detailX + 2 * (third + Ui.GAP), y, third, b -> {
                    LoadedPlacement lp = selectedPlacement();
                    if (lp == null) return;
                    lp.live = !lp.live;
                    BlockCompanionClient.changed(lp);
                    updateButtons();
                }));
    }

    private LoadedPlacement selectedPlacement() {
        LoadedEntry e = loaded == null ? null : loaded.getSelected();
        return e == null ? null : e.lp;
    }

    private void toggleShown() {
        LoadedPlacement lp = selectedPlacement();
        if (lp == null) return;
        lp.visible = !lp.visible;
        BlockCompanionClient.changed(lp);
        updateButtons();
    }

    private void setLocks(LockPreset p) {
        LoadedPlacement lp = selectedPlacement();
        if (lp == null) return;
        lp.locks.clear();
        lp.locks.addAll(p.locks);
        BlockCompanionClient.changed(lp);
        updateButtons();
    }

    /** "Locked in place", "Unlocked" and so on, with its colour. */
    private static String lockLabel(LoadedPlacement lp) {
        return switch (LockPreset.of(lp.locks)) {
            case NONE -> "Unlocked";
            case POSITION -> "Position locked";
            case PLACE -> "Locked in place";
            case ALL -> "Everything locked";
            case CUSTOM -> "Partly locked";
        };
    }

    private static int lockColor(LoadedPlacement lp) {
        return lp.locks.isEmpty() ? Ui.WARN : lp.locks.containsAll(PlacementLock.IN_PLACE) ? Ui.GOOD : Ui.INFO;
    }

    // ---- 3: resources -----------------------------------------------------------------------------------------------

    private void initResources() {
        List<LoadedPlacement> all = BlockCompanionClient.placements();
        counted = BlockCompanionClient.active();
        if (counted == null || !all.contains(counted)) counted = all.isEmpty() ? null : all.get(0);
        int n = 4, w = (pw - 12 - (n - 1) * Ui.GAP) / n, x = px + 6, y = py + 6;
        if (counted != null) {
            CycleButton<LoadedPlacement> which = addRenderableWidget(CycleButton.<LoadedPlacement>builder(lp -> Component.literal(lp.shortName()))
                    .withValues(all).withInitialValue(counted).displayOnlyValue()
                    .withTooltip(lp -> Tooltip.create(Component.literal("Which loaded schematic to count. Click for the next one.")))
                    .create(x, y, w, 20, Component.empty(), (b, lp) -> {
                        counted = lp;
                        recount();
                    }));
            which.active = all.size() > 1;
        }
        addRenderableWidget(CycleButton.booleanBuilder(Component.literal("Visible layers"), Component.literal("Whole build"))
                .withInitialValue(visibleOnly).displayOnlyValue().create(x + (w + Ui.GAP), y, w, 20, Component.empty(), (b, v) -> {
                    visibleOnly = v;
                    recount();
                }));
        addRenderableWidget(Ui.button("Refresh", "Count your inventory and linked chests again.", x + 2 * (w + Ui.GAP), y, w, b -> recount()));
        addRenderableWidget(Ui.button("Full screen", "The resource list on its own screen (key N).", x + 3 * (w + Ui.GAP), y, w, b -> {
            if (counted != null) minecraft.setScreen(new ResourceScreen(this, counted));
        })).active = counted != null;

        int top = py + 52, bottom = py + ph - 52;
        resources = new ResourceList(minecraft, px + 3, top, pw - 6, bottom - top);
        addRenderableWidget(resources);
        recount();

        // AutoBuild: the server builds the placement from the linked chests (Start), or its Pause / Resume and Stop.
        int ay = py + ph - 49;
        autoStart = addRenderableWidget(Ui.button("Start AutoBuild", null, px + 6, ay, 110, b -> {
            AutoBuildClient.start(counted);
            updateAuto();
        }));
        autoPause = addRenderableWidget(Ui.button("Pause", "Pause AutoBuild; Resume carries on where it stopped.", px + 6, ay, 60, b -> {
            Message.AutoBuildStatus st = AutoBuildClient.status(counted);
            boolean paused = st != null && st.state() == io.blockcompanion.core.autobuild.AutoBuildJob.State.PAUSED;
            AutoBuildClient.control(counted, paused ? Message.AutoBuildAction.RESUME : Message.AutoBuildAction.PAUSE);
        }));
        autoStop = addRenderableWidget(Ui.button("Stop", "Stop AutoBuild. What it placed stays.", px + 6 + 60 + Ui.GAP, ay, 46,
                b -> AutoBuildClient.control(counted, Message.AutoBuildAction.STOP)));
        autoOptions = addRenderableWidget(Ui.button("Options", "AutoBuild's options: speed, order, replacing blocks in the way, "
                + "clearing air, skipping missing items, a radius around you, only one kind of block. They change a running build too.",
                px + 6 + 110 + Ui.GAP, ay, AUTO_OPTIONS_W, b -> minecraft.setScreen(new AutoBuildScreen(this, counted))));
        updateAuto();

        ClientConfig c = BlockCompanionClient.config();
        int cy = py + ph - 25, tw = 110;
        addRenderableWidget(CycleButton.onOffBuilder(c.countChests)
                .withTooltip(v -> Tooltip.create(Component.literal("What your linked chests hold counts here, in the info panel and in the BlockCompanion Plugin.")))
                .create(px + pw - 6 - tw, cy, tw, 20, Component.literal("Chests"), (b, v) -> {
                    c.countChests = v;
                    BlockCompanionClient.configChanged();
                    recount();
                }));
    }

    private void recount() {
        if (resources != null) resources.count(counted, visibleOnly);
    }

    /** Start, or Pause / Resume and Stop while an AutoBuild of the counted placement is under way, and the line beside them. */
    private void updateAuto() {
        if (autoStart == null) return;
        Message.AutoBuildStatus st = AutoBuildClient.status(counted);
        boolean on = st != null && !st.state().over();
        autoStart.visible = !on;
        autoPause.visible = autoStop.visible = on;
        int w = px + pw - 6 - autoLineX();
        if (on) {
            boolean paused = st.state() == io.blockcompanion.core.autobuild.AutoBuildJob.State.PAUSED;
            autoPause.setMessage(Component.literal(paused ? "Resume" : "Pause"));
            setAutoLine(AutoBuildClient.line(st), st.state() == io.blockcompanion.core.autobuild.AutoBuildJob.State.RUNNING ? Ui.ACCENT : Ui.WARN, w);
            return;
        }
        AutoBuildClient.Readiness r = AutoBuildClient.check(counted);
        autoStart.active = r.canStart();
        if (!r.tip().equals(autoTip)) {
            autoTip = r.tip();
            autoStart.setTooltip(Tooltip.create(Component.literal(r.tip())));
        }
        if (st != null && !r.canStart() && !st.message().isEmpty()) {
            setAutoLine(st.message(), st.state() == io.blockcompanion.core.autobuild.AutoBuildJob.State.FINISHED ? Ui.GOOD : Ui.MUTED, w);
        } else {
            setAutoLine(r.canStart() ? String.format(Locale.ROOT, "Ready: %,d %s, %s", r.blocks(), r.blocks() == 1 ? "block" : "blocks",
                    AutoBuildClient.effective().describe()) : r.tip(), r.canStart() ? Ui.GOOD : Ui.MUTED, w);
        }
    }

    private void setAutoLine(String text, int color, int w) {
        autoLine = Ui.fit(font, text, w);
        autoLineColor = color;
    }

    /** The AutoBuild line next to its buttons: progress while it runs, else whether it can start. */
    private void drawAuto(GuiGraphics g) {
        if (autoStart == null) return;
        Ui.text(g, font, autoLine, autoLineX(), autoStart.getY() + 6, autoLineColor);
    }

    private static final int AUTO_OPTIONS_W = 56;

    /** Where the AutoBuild line starts: right of Start (or Pause and Stop) and the Options button. */
    private int autoLineX() {
        return px + 6 + 110 + Ui.GAP + AUTO_OPTIONS_W + 8;
    }

    /** The line about linked chests at the bottom of the Resources step, made again only when the chests or the tool change. */
    private String chestLine() {
        var chests = ChestTracker.get().chests();
        String tool = BlockCompanionClient.config().toolItem;
        if (chestLine == null || chests.version() != chestLineVersion || !tool.equals(chestLineTool)) {
            chestLineVersion = chests.version();
            chestLineTool = tool;
            chestLine = Ui.fit(font, chestText(chests, tool), pw - 130);
        }
        return chestLine;
    }

    private static String chestText(io.blockcompanion.core.chests.LinkedChests chests, String tool) {
        String how = tool.isBlank() ? "switch the selection tool on in Settings to link chests"
                : "Ctrl+right-click a chest with the " + tool.substring(tool.indexOf(':') + 1).replace('_', ' ') + " to link it";
        if (chests.size() == 0) return "No linked chests: " + how + ".";
        return chests.size() + (chests.size() == 1 ? " linked chest" : " linked chests")
                + (chests.unknown() > 0 ? " (" + chests.unknown() + " not seen yet: open them once)" : "") + "; " + how + ".";
    }

    // ---- 4: BlockDesigner -------------------------------------------------------------------------------------------

    private void initLink() {
        linkList = new OptionList(minecraft, px + 3, py + 3, pw - 6, ph - 6);
        addRenderableWidget(linkList);
        LinkPanel.add(linkList);
        List<LoadedPlacement> fromBd = BlockCompanionClient.placements().stream().filter(LoadedPlacement::fromBlockDesigner).toList();
        if (!fromBd.isEmpty()) {
            linkList.header("Follow BlockDesigner");
            for (LoadedPlacement lp : fromBd) {
                linkList.option(lp.shortName(), "Reload it when BlockDesigner sends a new version (its Live button).",
                        Ui.onOff(lp.live, v -> {
                            lp.live = v;
                            BlockCompanionClient.changed(lp);
                        }, null));
            }
        }
    }

    // ---- state ------------------------------------------------------------------------------------------------------

    private void updateButtons() {
        if (loadButton != null) {
            FileEntry sel = files == null ? null : files.getSelected();
            loadButton.active = deleteButton.active = sel != null;
            fileUnloadButton.active = sel != null && !loadedFrom(sel.entry.name()).isEmpty();
        }
        if (shareButton != null) {
            SyncClient s = sync();
            Features f = s == null ? Features.NONE : s.features();
            boolean on = s != null && s.serverPresent() && f.syncEnabled();
            boolean admin = f.can(Permission.ADMIN);
            Object selected = shared.selected();
            shareButton.active = on && BlockCompanionClient.placement() != null && (f.can(Permission.PLACE) || admin);
            sharedLoad.active = on && selected != null;
            sharedLock.active = on && selected instanceof SharedPlacement p && (admin || (p.owner().equals(s.self()) && f.can(Permission.LOCK)));
            sharedLock.setMessage(Component.literal(selected instanceof SharedPlacement p && p.locked() ? "Unlock" : "Lock"));
            sharedDelete.active = on && (selected instanceof SharedPlacement p ? !p.locked() || admin || p.owner().equals(s.self())
                    : selected instanceof SchematicInfo i && (admin || i.uploader().equals(s.self())));
            sharedUnlink.active = on && s.linked() != null;
        }
        if (showButton != null) {
            LoadedPlacement lp = selectedPlacement();
            boolean any = lp != null;
            for (Button b : lockButtons) b.visible = any;
            showButton.visible = hereButton.visible = turnButton.visible = mirrorButton.visible = unloadButton.visible = liveButton.visible = any;
            editButton.visible = any;
            if (any) {
                LockPreset current = LockPreset.of(lp.locks);
                LockPreset[] presets = {LockPreset.NONE, LockPreset.POSITION, LockPreset.PLACE, LockPreset.ALL};
                for (int i = 0; i < lockButtons.size(); i++) lockButtons.get(i).active = presets[i] != current;
                showButton.setMessage(Component.literal(lp.visible ? "Hide" : "Show"));
                hereButton.active = BlockCompanionClient.here(lp) && !lp.locked(PlacementLock.POSITION);
                turnButton.active = !lp.locked(PlacementLock.ROTATION);
                mirrorButton.active = !lp.locked(PlacementLock.MIRROR);
                liveButton.setMessage(Component.literal("Live: ").append(Component.literal(lp.live ? "On" : "Off").withColor(lp.live ? Ui.GOOD : Ui.MUTED)));
                liveButton.active = lp.fromBlockDesigner();
                ClientLink.State link = ClientLink.state();
                editButton.active = link == ClientLink.State.CONNECTED;
                String tip = switch (link) {
                    case OFF -> "The link to BlockDesigner is off: start it on the BlockDesigner step.";
                    case WAITING -> "BlockDesigner isn't connected: open the BlockCompanion Plugin there.";
                    case CONNECTED -> "Opens it in BlockDesigner as the current project. It then follows BlockDesigner: "
                            + "your changes there show up here live.";
                };
                if (!tip.equals(editTip)) {
                    editTip = tip;
                    editButton.setTooltip(Tooltip.create(Component.literal(tip)));
                }
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (linkList != null) linkList.tick();
        if (resources != null) resources.tick();
        updateAuto();
        updateStepLines();
        if (showButton != null) updateButtons();
    }

    private String[] stepLines() {
        if (stepLines == null) updateStepLines();
        return stepLines;
    }

    private void updateStepLines() {
        Step[] steps = Step.values();
        if (stepLines == null) stepLines = new String[steps.length];
        int barW = Math.min(width - 2 * Ui.PAD, 560), sw = (barW - (steps.length - 1) * Ui.GAP) / steps.length;
        for (int i = 0; i < steps.length; i++) stepLines[i] = Ui.fit(font, stepStatus(steps[i]), sw - 14);
    }

    /** The grey line under each step's button: how far that step is. */
    private String stepStatus(Step s) {
        List<LoadedPlacement> all = BlockCompanionClient.placements();
        LoadedPlacement active = BlockCompanionClient.active();
        return switch (s) {
            case SOURCE -> server ? (serverPresent() ? "Server" : "No server") : "This computer";
            case PLACEMENT -> all.isEmpty() ? "Nothing loaded" : active == null ? all.size() + " loaded" : lockLabel(active);
            case RESOURCES -> {
                if (active == null) yield "-";
                ProgressTracker t = active.progress().tracker();
                yield t == null ? "Counting..." : String.format(Locale.ROOT, "%.0f%% built", Math.floor(t.totals().fraction() * 100));
            }
            case LINK -> switch (ClientLink.state()) {
                case OFF -> "Link off";
                case WAITING -> "Waiting";
                case CONNECTED -> "Connected";
            };
        };
    }

    private int stepColor(Step s) {
        LoadedPlacement active = BlockCompanionClient.active();
        return switch (s) {
            case SOURCE -> BlockCompanionClient.placements().isEmpty() ? Ui.WARN : Ui.GOOD;
            case PLACEMENT -> active == null ? Ui.DIM : lockColor(active);
            case RESOURCES -> {
                ProgressTracker t = active == null ? null : active.progress().tracker();
                yield t == null ? Ui.DIM : t.totals().fraction() >= 1 ? Ui.GOOD : Ui.WARN;
            }
            case LINK -> LinkPanel.stateColor();
        };
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, px, py, pw, ph);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        String note = switch (step) {
            case SOURCE -> server ? "Shared on this server" : BlockCompanionClient.library().root().toString();
            case PLACEMENT -> BlockCompanionClient.placements().size() + " loaded in this world";
            case RESOURCES -> "What the build still needs";
            case LINK -> "Live link to BlockDesigner's BlockCompanion Plugin";
        };
        Ui.titleBar(g, font, title, note, width);

        // Step statuses under the step buttons, with a light for each and the current step underlined.
        Step[] steps = Step.values();
        int barW = Math.min(width - 2 * Ui.PAD, 560);
        int barX = (width - barW) / 2, sw = (barW - (steps.length - 1) * Ui.GAP) / steps.length;
        for (int i = 0; i < steps.length; i++) {
            int x = barX + i * (sw + Ui.GAP);
            if (steps[i] == step) Ui.underline(g, x, stepBarY() + 20, sw);
            Ui.dot(g, x + 3, stepBarY() + 24, stepColor(steps[i]));
            Ui.text(g, font, stepLines()[i], x + 13, stepBarY() + 24, Ui.MUTED);
        }

        switch (step) {
            case SOURCE -> {
                if (server) drawShared(g);
                else drawLocal(g);
            }
            case PLACEMENT -> drawPlacement(g);
            case RESOURCES -> {
                Ui.text(g, font, Ui.fit(font, resources.summary(), pw - 12), px + 6, py + 30, Ui.MUTED);
                resources.headings(g, py + 41);
                if (counted == null) Ui.centered(g, font, "Load a schematic first (step 1).", px + pw / 2, resources.getY() + 10, Ui.MUTED);
                else if (resources.children().isEmpty()) {
                    Ui.centered(g, font, resources.total() > 0 ? "Everything is placed" : "Counting...", px + pw / 2, resources.getY() + 10, Ui.GOOD);
                }
                Ui.text(g, font, chestLine(), px + 6, py + ph - 19, Ui.MUTED);
                drawAuto(g);
            }
            case LINK -> {
            }
        }
    }

    private void drawLocal(GuiGraphics g) {
        int x = px + 6 + 2 * Math.min(100, (pw - 16) / 2) + Ui.GAP + 8;
        Ui.text(g, font, Ui.fit(font, "Projects and .schem files first", px + pw - 6 - x), x, py + 12, Ui.DIM);
        if (error != null) Ui.text(g, font, error, files.getX() + 4, files.getY() + 6, Ui.BAD);
        else if (files.children().isEmpty()) {
            Ui.wrapped(g, font, Component.translatable("blockcompanion.library.empty"), files.getX() + 6, files.getY() + 6, files.getWidth() - 12, Ui.MUTED, 4);
        }
    }

    private void drawShared(GuiGraphics g) {
        SyncClient s = sync();
        Ui.text(g, font, Ui.fit(font, serverStatus(s), pw - 12), px + 6, py + 30, s != null && s.serverPresent() ? Ui.MUTED : Ui.WARN);
        List<String> activity = s == null ? List.of() : s.activity();
        if (!activity.isEmpty()) {
            int x = px + 6 + 2 * Math.min(100, (pw - 16) / 2) + Ui.GAP + 8;
            Ui.text(g, font, Ui.fit(font, String.join("   ", activity), px + pw - 6 - x), x, py + 12, Ui.ACCENT);
        }
        if (s == null || !s.serverPresent()) {
            Ui.wrapped(g, font, Component.literal("Shared schematics need BlockCompanion on the server: the mod on a Fabric or NeoForge server, or the "
                    + "Paper plugin. Singleplayer uses This computer."), shared.getX() + 6, shared.getY() + 6, shared.getWidth() - 12, Ui.MUTED, 4);
        }
    }

    private void drawPlacement(GuiGraphics g) {
        LoadedPlacement lp = selectedPlacement();
        if (BlockCompanionClient.placements().isEmpty()) {
            Ui.wrapped(g, font, Component.literal("Nothing loaded. Pick a schematic in step 1 and press Load; load as many as you like."),
                    loaded.getX() + 6, loaded.getY() + 6, loaded.getWidth() - 12, Ui.MUTED, 4);
        }
        int x = detailX, y = py + 8, w = detailW;
        if (lp == null) {
            if (!BlockCompanionClient.placements().isEmpty()) Ui.wrapped(g, font, Component.literal("Select a placement on the left."), x, y, w, Ui.MUTED, 2);
            return;
        }
        Ui.shadowed(g, font, Ui.fit(font, lp.shortName(), w - editW - Ui.GAP), x, y, Ui.ACCENT);
        y += 12;
        String where = dimension(lp.dimension) + "  " + lp.placement.origin().x() + ", " + lp.placement.origin().y() + ", " + lp.placement.origin().z();
        String turn = (lp.placement.rotation() != 0 ? lp.placement.rotation() * 90 + "°" : "not turned") + (lp.placement.mirrored() ? ", mirrored" : "");
        Ui.text(g, font, Ui.fit(font, where, w - editW - Ui.GAP), x, y, Ui.SOFT);
        y += 10;
        Ui.text(g, font, Ui.fit(font, turn + (lp.visible ? "" : ", hidden"), w), x, y, Ui.MUTED);
        y += 13;
        ProgressTracker t = lp.progress().tracker();
        if (t != null) {
            double f = t.totals().fraction();
            String pct = String.format(Locale.ROOT, "%.0f%%", Math.floor(f * 100));
            int bw = w - font.width(pct) - 6;
            if (f >= 1) Bars.solid(g, x, y + 2, bw, 1, 0xFFFFD75A);
            else Bars.gradient(g, x, y + 2, bw, f);
            Ui.rightText(g, font, pct, x + w, y, Ui.TEXT);
        }
        y += 13;
        Ui.dot(g, x, y, lockColor(lp));
        Ui.text(g, font, Ui.fit(font, lockLabel(lp), w - 12), x + 11, y, Ui.TEXT);
    }

    private static String dimension(String id) {
        String p = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        return p.isEmpty() ? id : Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A line about a project: its layers. */
    private String describe(SchematicLibrary.Entry e) {
        if (!e.isProject()) return e.preferred() ? null : e.kind().label() + " file: loaded for compatibility, saves are .schem";
        String key;
        try {
            key = e.name() + "@" + Files.getLastModifiedTime(e.path()).toMillis();
        } catch (IOException ex) {
            key = e.name();
        }
        return projectInfo.computeIfAbsent(key, k -> {
            try {
                BdProject.Info info = BdProject.readInfo(e.path());
                StringBuilder b = new StringBuilder("Layers: ");
                int hidden = 0;
                for (int i = 0; i < info.layers().size(); i++) {
                    BdProject.LayerInfo l = info.layers().get(i);
                    if (i > 0) b.append(", ");
                    b.append(l.name());
                    if (!l.visible()) {
                        b.append(" (hidden)");
                        hidden++;
                    }
                }
                if (info.layers().isEmpty()) b.append("none");
                if (hidden > 0) b.append("; hidden layers are not placed");
                return b.toString();
            } catch (IOException | RuntimeException ex) {
                return "Can't read this project: " + ex.getMessage();
            }
        });
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Lists here draw no background of their own: the panel is behind them. */
    private abstract static class PanelList<E extends ObjectSelectionList.Entry<E>> extends ObjectSelectionList<E> {
        PanelList(Minecraft mc, int w, int h, int y, int rowHeight) {
            super(mc, w, h, y, rowHeight);
        }

        @Override
        public int getRowWidth() {
            return width - 18;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 7;
        }

        @Override
        protected void renderListBackground(GuiGraphics g) {
        }

        @Override
        protected void renderListSeparators(GuiGraphics g) {
        }
    }

    // ---- the library list -------------------------------------------------------------------------------------------

    private final class FileList extends PanelList<FileEntry> {
        FileList(Minecraft mc, int w, int h, int y) {
            super(mc, w, h, y, 14);
        }

        void set(List<SchematicLibrary.Entry> entries) {
            clearEntries();
            for (SchematicLibrary.Entry e : entries) addEntry(new FileEntry(e));
        }

        @Override
        public void setSelected(FileEntry e) {
            super.setSelected(e);
            updateButtons();
        }
    }

    private final class FileEntry extends ObjectSelectionList.Entry<FileEntry> {
        final SchematicLibrary.Entry entry;

        FileEntry(SchematicLibrary.Entry entry) {
            this.entry = entry;
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = rowTop, left = rowLeft, w = rowWidth;
            long copies = BlockCompanionClient.placements().stream().filter(lp -> lp.name().equals(entry.name())).count();
            int color = copies > 0 ? Ui.ACCENT : entry.preferred() ? Ui.TEXT : 0xFFB8B8B8;
            String size = size(entry.size());
            String kind = entry.kind().label();
            int right = left + w - 2;
            Ui.text(g, font, size, right - font.width(size), top + 2, Ui.MUTED);
            int kx = right - 52 - font.width(kind);
            Ui.text(g, font, kind, kx, top + 2, entry.preferred() ? 0xFF7CD67C : Ui.MUTED);
            String name = entry.name() + (copies > 1 ? "  ×" + copies : "");
            Ui.text(g, font, Ui.fit(font, name, kx - left - 8), left + 2, top + 2, color);
            if (hovering) {
                String info = describe(entry);
                if (info != null) Ui.tooltip(g, font, Component.literal(info), mouseX, mouseY);
            }
        }

        private long lastClick;

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            files.setSelected(this);
            long now = Util.getMillis();
            if (now - lastClick < 300) loadSelected();
            lastClick = now;
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.name());
        }
    }

    // ---- the shared list --------------------------------------------------------------------------------------------

    private final class SharedList extends PanelList<SharedEntry> {
        SharedList(Minecraft mc, int w, int h, int y) {
            super(mc, w, h, y, 14);
        }

        Object selected() {
            SharedEntry e = getSelected();
            return e == null ? null : e.row;
        }

        void rebuild() {
            Object keep = selected();
            clearEntries();
            SyncClient s = sync();
            if (s == null || !s.serverPresent() || !s.features().syncEnabled()) return;
            List<SharedPlacement> placements = new ArrayList<>(s.placements());
            placements.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            addEntry(new SharedEntry("Placements"));
            for (SharedPlacement p : placements) addEntry(new SharedEntry(p));
            addEntry(new SharedEntry("Schematics"));
            for (SchematicInfo i : s.schematics()) addEntry(new SharedEntry(i));
            // Keep the selection if it still exists (by id or hash, since records are replaced on every update).
            for (SharedEntry e : children()) {
                if (e.row instanceof SharedPlacement p && keep instanceof SharedPlacement q && p.id().equals(q.id())) super.setSelected(e);
                if (e.row instanceof SchematicInfo i && keep instanceof SchematicInfo j && i.hash().equals(j.hash())) super.setSelected(e);
            }
        }

        @Override
        public void setSelected(SharedEntry e) {
            super.setSelected(e);
            updateButtons();
        }
    }

    private final class SharedEntry extends ObjectSelectionList.Entry<SharedEntry> {
        /** A heading (String), a placement or a schematic. */
        final Object row;

        SharedEntry(Object row) {
            this.row = row;
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = rowTop, left = rowLeft, w = rowWidth, right = left + w - 2;
            SyncClient s = sync();
            if (row instanceof String header) {
                Ui.section(g, font, header, left + 2, top + 3, w - 4);
            } else if (row instanceof SharedPlacement p) {
                String state = p.editor() != null ? p.editorName() + " moving" : p.locked() ? "locked" : "";
                String info = p.ownerName() + "  " + p.x() + " " + p.y() + " " + p.z() + (state.isEmpty() ? "" : "  [" + state + "]");
                boolean linked = s != null && p.id().equals(s.linked());
                int color = linked ? Ui.ACCENT : p.locked() ? 0xFFFFB070 : Ui.TEXT;
                Ui.text(g, font, Ui.fit(font, (linked ? "> " : "  ") + p.name(), w - font.width(info) - 12), left + 2, top + 2, color);
                Ui.rightText(g, font, info, right, top + 2, Ui.MUTED);
            } else if (row instanceof SchematicInfo i) {
                String info = i.uploaderName() + "  " + size(i.size());
                Ui.text(g, font, Ui.fit(font, "  " + i.name(), w - font.width(info) - 12), left + 2, top + 2, Ui.TEXT);
                Ui.rightText(g, font, info, right, top + 2, Ui.MUTED);
            }
        }

        private long lastClick;

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (row instanceof String) return false;
            shared.setSelected(this);
            long now = Util.getMillis();
            if (now - lastClick < 300) loadShared();
            lastClick = now;
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(Objects.toString(row));
        }
    }

    // ---- the loaded list --------------------------------------------------------------------------------------------

    private final class LoadedList extends PanelList<LoadedEntry> {
        LoadedList(Minecraft mc, int w, int h, int y) {
            super(mc, w, h, y, 26);
        }

        void set(List<LoadedPlacement> list) {
            LoadedPlacement keep = selectedPlacement() != null ? selectedPlacement() : BlockCompanionClient.active();
            clearEntries();
            for (LoadedPlacement lp : list) {
                LoadedEntry e = new LoadedEntry(lp);
                addEntry(e);
                if (lp == keep) super.setSelected(e);
            }
        }

        @Override
        public void setSelected(LoadedEntry e) {
            super.setSelected(e);
            if (e != null) BlockCompanionClient.setActive(e.lp);
            updateButtons();
        }
    }

    private final class LoadedEntry extends ObjectSelectionList.Entry<LoadedEntry> {
        final LoadedPlacement lp;

        LoadedEntry(LoadedPlacement lp) {
            this.lp = lp;
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = rowTop, left = rowLeft, w = rowWidth;
            boolean active = lp == BlockCompanionClient.active();
            int right = left + w - 2;
            ProgressTracker t = lp.progress().tracker();
            String pct = t == null ? "" : String.format(Locale.ROOT, "%.0f%%", Math.floor(t.totals().fraction() * 100));
            Ui.text(g, font, pct, right - font.width(pct), top + 2, Ui.TEXT);
            Ui.dot(g, left + 2, top + 2, lockColor(lp));
            StringBuilder tags = new StringBuilder();
            if (!lp.visible) tags.append("hidden ");
            if (lp.live) tags.append("live ");
            String tagText = tags.toString().trim();
            int tx = right - font.width(pct) - 6 - font.width(tagText);
            Ui.text(g, font, tagText, tx, top + 2, Ui.INFO);
            Ui.text(g, font, Ui.fit(font, lp.shortName(), tx - left - 18), left + 13, top + 2, active ? Ui.ACCENT : lp.visible ? Ui.TEXT : Ui.MUTED);
            String where = lp.placement.origin().x() + ", " + lp.placement.origin().y() + ", " + lp.placement.origin().z();
            Ui.text(g, font, Ui.fit(font, where, w - 80), left + 13, top + 13, Ui.MUTED);
            if (t != null) {
                double f = t.totals().fraction();
                if (f >= 1) Bars.solid(g, right - 60, top + 14, 60, 1, 0xFFFFD75A);
                else Bars.gradient(g, right - 60, top + 14, 60, f);
            }
            if (hovering) {
                Ui.tooltip(g, font, Component.literal(lp.name() + "\n" + dimension(lp.dimension) + "\n" + PlacementLock.describe(lp.locks)
                        + (lp.fromBlockDesigner() ? "\nFrom BlockDesigner" + (lp.live ? ", follows its changes" : ", not following") : "")), mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            loaded.setSelected(this);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(lp.shortName());
        }
    }
}
