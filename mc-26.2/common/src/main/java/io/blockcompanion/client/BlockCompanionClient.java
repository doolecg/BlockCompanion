package io.blockcompanion.client;

import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.easyplace.EasyPlace;
import io.blockcompanion.client.fx.Effects;
import io.blockcompanion.client.hud.Hud;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.client.progress.BuildProgress;
import io.blockcompanion.client.render.GhostRenderer;
import io.blockcompanion.client.render.BoxRenderer;
import io.blockcompanion.client.render.SelectionRenderer;
import io.blockcompanion.client.screen.GuideScreen;
import io.blockcompanion.client.screen.LibraryScreen;
import io.blockcompanion.client.screen.ResourceScreen;
import io.blockcompanion.client.screen.SaveScreen;
import io.blockcompanion.client.screen.SettingsScreen;
import io.blockcompanion.client.tool.SelectionTool;
import io.blockcompanion.core.hud.BoxLook;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.placement.PlacementHistory;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.placement.RayBox;
import io.blockcompanion.core.placement.SavedPlacement;
import io.blockcompanion.core.placement.SavedPlacements;
import io.blockcompanion.core.placement.Selection;
import io.blockcompanion.core.placement.ToolMode;
import io.blockcompanion.core.placement.UndoTimeline;
import io.blockcompanion.core.progress.OwnPlacements;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client side of BlockCompanion, shared by Fabric and NeoForge. The platform entry points forward ticks, world
 * rendering and HUD rendering here and register {@link Keys#ALL}; mixins forward mouse scrolling, clicks and world
 * section updates. The logic itself (formats, transforms, layers, comparing, item counts, the link) lives in the core
 * library.
 *
 * <p>Several schematics can be loaded at once ({@link LoadedPlacement}). The keys act on the one the player looks at
 * (its box turns yellow), or else on the selected one ({@link #active()}), which the schematic list sets.
 */
public final class BlockCompanionClient {
    public static final Logger LOG = LoggerFactory.getLogger("BlockCompanion");

    private static ClientConfig config = new ClientConfig();
    private static Path configFile;
    private static SchematicLibrary library;
    private static Path placementsDir;
    private static String loader = "?", modVersion = "?";
    private static final Selection SELECTION = new Selection();
    private static final SelectionRenderer SELECTION_RENDER = new SelectionRenderer();
    private static final Effects EFFECTS = new Effects();
    private static final EasyPlace EASY = new EasyPlace();
    private static final OwnPlacements OWN = new OwnPlacements();
    /** The material helper: the held item, and when it was looked up. */
    private static String helperItem;
    private static long helperVersion = -1;
    private static int helperTicks;

    private static final List<LoadedPlacement> PLACEMENTS = new ArrayList<>();
    private static LoadedPlacement active;
    /** The placement following a shared one on the server, if any. */
    private static LoadedPlacement sharedLink;
    /** Placements sent to BlockDesigner with Edit in BlockDesigner, by slot, until the app answers. */
    private static final Map<Integer, LoadedPlacement> EDITS = new HashMap<>();
    /** The placement whose box the player's view ray meets this frame, and where; null when none. */
    private static LoadedPlacement hovered;
    private static RayBox.Hit hover;
    /** Where the view ray meets the save selection's box this frame when that box is the one looked at; else null. */
    private static RayBox.Hit selectionHover;

    private static Level lastLevel;
    private static String worldKey;
    private static SavedPlacements saved;
    private static int ticksSinceChange;
    /** Ticks into a newly joined world, until the welcome line (and the first-time guide) is due; -1 when done. */
    private static int welcomeTicks = -1;
    private static double scrollRemainder;
    /** The placement the player changed last: what undo and redo act on. */
    /** Undo and redo across every placement and the selection's moves, in the order the changes were made. */
    private static final UndoTimeline<Object> UNDO = new UndoTimeline<>(t -> t == SELECTION ? SELECTION.history() : ((LoadedPlacement) t).history);
    /** "Only this one" in the view cycle (the view key): the placement shown alone, and the ones it hid. */
    private static LoadedPlacement soloOwner;
    private static final List<LoadedPlacement> SOLO_HIDDEN = new ArrayList<>();
    /** When the last hint showed. */
    private static long lastHint;

    private BlockCompanionClient() {
    }

    /** The platform's name and the mod's version, for the link's instance file; call before {@link #init()}. */
    public static void setPlatform(String loaderName, String version) {
        loader = loaderName;
        modVersion = version;
    }

    /** Called once by the platform's client entry point. */
    public static void init() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path root = gameDir.resolve("blockcompanion");
        library = new SchematicLibrary(root.resolve("schematics"));
        placementsDir = root.resolve("placements");
        configFile = gameDir.resolve("config").resolve("blockcompanion.properties");
        config = ClientConfig.load(configFile);
        try {
            library.list();
        } catch (IOException e) {
            LOG.warn("Could not create the schematic folder {}: {}", library.root(), e.toString());
        }
        if (config.link) ClientLink.start(library.root(), loader, modVersion);
        LOG.info("BlockCompanion ready; schematics in {}", library.root());
    }

    public static ClientConfig config() {
        return config;
    }

    /** Saves the config after a change on the settings screen, and applies what needs more than a new value. */
    public static void configChanged() {
        config.save(configFile);
        for (LoadedPlacement lp : PLACEMENTS) lp.ghosts.invalidate();
    }

    /**
     * Starts the live link to BlockDesigner now (the Start button); {@code config.link} only says whether it starts
     * with the game.
     */
    public static void startLink() {
        ClientLink.start(library.root(), loader, modVersion);
        if (ClientLink.running()) actionBar("Link on: BlockDesigner's BlockCompanion Plugin finds this game in a few seconds");
        else actionBar("The link could not start: " + ClientLink.problem());
    }

    /** Stops the live link (the Stop button): BlockDesigner is disconnected and no longer sees this game. */
    public static void stopLink() {
        ClientLink.stop();
        actionBar("Link to BlockDesigner off");
    }

    public static SchematicLibrary library() {
        return library;
    }

    // ---- placements -------------------------------------------------------------------------------------------------

    /** Every loaded placement, in load order. */
    public static List<LoadedPlacement> placements() {
        return List.copyOf(PLACEMENTS);
    }

    /** The selected placement: what keys act on when the player doesn't look at a box. */
    public static LoadedPlacement active() {
        return active;
    }

    public static void setActive(LoadedPlacement lp) {
        if (lp != null && PLACEMENTS.contains(lp)) active = lp;
    }

    /** The selected placement's schematic in the world, or null. */
    public static Placement placement() {
        return active == null ? null : active.placement;
    }

    private static final Layers NO_LAYERS = new Layers();

    /** The selected placement's layer view (an unused one when none is loaded). */
    public static Layers layers() {
        return active == null ? NO_LAYERS : active.layers;
    }

    /** The build progress of the selected placement, or null. */
    public static ProgressTracker progress() {
        return active == null ? null : active.progress.tracker();
    }

    static BuildProgress buildProgress() {
        return active == null ? new BuildProgress() : active.progress;
    }

    /** The placement the keys act on: the one looked at, else the selected one when it is in this dimension. */
    public static LoadedPlacement focus() {
        if (hovered != null && PLACEMENTS.contains(hovered)) return hovered;
        return active != null && here(active) ? active : null;
    }

    /** The one whose box the player looks at, or null. */
    public static LoadedPlacement hovered() {
        return hovered;
    }

    /** Placements shown in the player's dimension. */
    public static List<LoadedPlacement> shownHere() {
        List<LoadedPlacement> out = new ArrayList<>();
        for (LoadedPlacement lp : PLACEMENTS) if (lp.visible && here(lp)) out.add(lp);
        return out;
    }

    /** Placements in the player's dimension, shown or hidden. */
    private static List<LoadedPlacement> hereAll() {
        List<LoadedPlacement> out = new ArrayList<>();
        for (LoadedPlacement lp : PLACEMENTS) if (here(lp)) out.add(lp);
        return out;
    }

    /** True if something is loaded and shown in the player's dimension. */
    public static boolean activeHere() {
        return !shownHere().isEmpty();
    }

    /** True if the placement lives in the player's dimension, shown or hidden: its progress is followed there. */
    public static boolean here(LoadedPlacement lp) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && dimensionId(mc.level).equals(lp.dimension);
    }

    /** The two corners marked with the tool or the corner key. */
    public static Selection selection() {
        return SELECTION;
    }

    /** A box to save, where it comes from, and a name to offer. */
    public record SaveRegion(Box box, String source, String suggestedName) {
    }

    /** What the save screen would save: the marked corners, or else the focused placement's box; null if neither. */
    public static SaveRegion saveRegion() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (SELECTION.isComplete() && dimensionId(mc.level).equals(SELECTION.dimension())) {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"));
            return new SaveRegion(SELECTION.box().orElseThrow(), "marked corners", "Build " + stamp);
        }
        LoadedPlacement f = focus();
        if (f != null) return new SaveRegion(f.placement.worldBox(), "the loaded schematic's box", f.shortName() + " (built)");
        return null;
    }

    /** The key currently bound to one of our actions, for messages. */
    public static String keyName(String action) {
        net.minecraft.client.KeyMapping k = switch (action) {
            case "select_corner" -> Keys.SELECT_CORNER;
            case "save" -> Keys.SAVE;
            case "lock" -> Keys.LOCK;
            case "undo" -> Keys.UNDO;
            case "redo" -> Keys.REDO;
            default -> Keys.LIBRARY;
        };
        return k.getTranslatedKeyMessage().getString();
    }

    private static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }

    // ---- ticking and keys -----------------------------------------------------------------------------------------

    public static void onClientTick(Minecraft mc) {
        if (mc.level != lastLevel) onLevelChanged(mc);
        ClientLink.tick();
        if (TutorialSelfTest.ENABLED) TutorialSelfTest.tick(mc);
        if (mc.level == null || mc.player == null) return;

        welcome(mc);
        Tutorial.tick(mc);
        while (Keys.LIBRARY.consumeClick()) mc.gui.setScreen(new LibraryScreen(mc.gui.screen()));
        while (Keys.RESOURCES.consumeClick()) {
            if (PLACEMENTS.isEmpty()) actionBar("No schematic loaded");
            else mc.gui.setScreen(new ResourceScreen(mc.gui.screen(), focus() != null ? focus() : active));
        }
        while (Keys.SETTINGS.consumeClick()) mc.gui.setScreen(new SettingsScreen(mc.gui.screen()));
        while (Keys.SELECT_CORNER.consumeClick()) markCorner(mc);
        while (Keys.SAVE.consumeClick()) mc.gui.setScreen(new SaveScreen(mc.gui.screen()));
        while (Keys.GRAB.consumeClick()) grab();
        while (Keys.NEXT_PLACEMENT.consumeClick()) selectNext();
        while (Keys.TOGGLE_VISIBLE.consumeClick()) {
            LoadedPlacement f = focus() != null ? focus() : active;
            if (f == null) continue;
            f.visible = !f.visible;
            changed(f);
            actionBar(f.shortName() + (f.visible ? " shown" : " hidden"));
        }
        // Undo and redo are handled as keys are pressed (KeyboardHandlerMixin), with Ctrl. The redo key
        // shares Y with the lock key by default; on versions where one key clicks only one mapping, its clicks lock.
        while (Keys.UNDO.consumeClick()) {
            // Handled by the mixin.
        }
        int redoClicks = 0, lockClicks = 0;
        while (Keys.REDO.consumeClick()) redoClicks++;
        while (Keys.LOCK.consumeClick()) lockClicks++;
        if (Keys.REDO.same(Keys.LOCK)) lockClicks = Math.max(lockClicks, redoClicks);
        for (int i = 0; i < lockClicks; i++) {
            LoadedPlacement f = focus();
            if (f == null) continue;
            boolean lock = !f.locks.containsAll(PlacementLock.IN_PLACE);
            if (lock) f.locks.addAll(PlacementLock.IN_PLACE);
            else f.locks.clear();
            changed(f);
            actionBar(f.shortName() + ": " + PlacementLock.describe(f.locks));
        }
        while (Keys.MIRROR.consumeClick()) {
            LoadedPlacement f = focus();
            if (f == null || needsTool(mc, "mirror it") || refuse(f, PlacementLock.MIRROR)) continue;
            f.placement.toggleMirror();
            changed(f);
            actionBar(f.placement.mirrored() ? "Mirrored" : "Not mirrored");
        }
        while (Keys.VIEW.consumeClick()) {
            // The looked-at placement (or the selected one: a hidden one is brought back this way).
            LoadedPlacement f = focus();
            if (f == null) {
                actionBar(PLACEMENTS.isEmpty() ? "No schematic loaded" : "Look at a schematic's box to change its view");
                continue;
            }
            View v = cycleView(f, 1);
            active = f;
            changed(f);
            actionBar(f.shortName() + ": " + v.label);
        }
        while (Keys.LAYER_UP.consumeClick()) stepLayer(1);
        while (Keys.LAYER_DOWN.consumeClick()) stepLayer(-1);
        while (Keys.EASY_PLACE.consumeClick()) {
            config.easyPlace = !config.easyPlace;
            config.save(configFile);
            if (config.easyPlace && !EasyPlace.serverAllows()) actionBar("Easy place is switched off on this server");
            else actionBar(config.easyPlace ? "Easy place on: right-click a ghost with its block" : "Easy place off");
        }
        while (Keys.EASY_PLACE_AUTO.consumeClick()) {
            config.easyPlaceAuto = !config.easyPlaceAuto;
            config.save(configFile);
            if (!config.easyPlaceAuto) actionBar("Auto place off");
            else if (!EasyPlace.serverAllows() || !EasyPlace.autoServerAllows()) actionBar("Auto place is switched off on this server");
            else if (!config.easyPlace) actionBar("Auto place on, but easy place is off (" + keyName("easy_place") + ")");
            else actionBar("Auto place on: missing blocks in reach place themselves");
        }
        while (Keys.TOGGLE_HUD.consumeClick()) {
            // Any HUD piece showing: hide all of them; none showing: show all of them.
            boolean show = !(config.progressHud || config.crosshairHint || config.toolHud);
            config.progressHud = config.crosshairHint = config.toolHud = show;
            config.save(configFile);
            actionBar(show ? "HUD shown" : "HUD hidden");
        }
        while (Keys.TOGGLE_SHIMMER.consumeClick()) {
            config.ghostShimmer = !config.ghostShimmer;
            config.save(configFile);
            actionBar(config.ghostShimmer ? "Ghost shimmer on" : "Ghost shimmer off");
        }
        while (Keys.TOGGLE_GHOST_ENTITIES.consumeClick()) {
            config.ghostBlockEntities = !config.ghostBlockEntities;
            config.save(configFile);
            actionBar(config.ghostBlockEntities ? "Block entity ghosts on" : "Block entity ghosts off");
        }
        while (Keys.TOGGLE_BOXES.consumeClick()) {
            config.boxesAlways = !config.boxesAlways;
            config.save(configFile);
            actionBar(config.boxesAlways ? "Boxes always shown" : "Boxes shown with the tool");
        }
        while (Keys.TOGGLE_MATERIAL_HELPER.consumeClick()) {
            config.materialHelper = !config.materialHelper;
            config.save(configFile);
            actionBar(config.materialHelper ? "Material helper on" : "Material helper off");
        }
        while (Keys.LAYER_MODE.consumeClick()) {
            LoadedPlacement f = focus();
            if (f == null || refuse(f, PlacementLock.LAYERS)) continue;
            int feet = mc.player.getBlockY() - f.placement.worldBox().minY();
            f.layers.toggleMode(feet, f.placement.localSizeY());
            changed(f);
            actionBar(f.layers.mode() == Layers.Mode.SINGLE ? "Single layer" : "Layers build up");
        }

        tickProgress(mc);
        ChestTracker.get().tick(mc);
        if (FORMATS_SELF_TEST) formatsSelfTest(mc);
        if (PLACE_SELF_TEST) PlaceSelfTest.tick(mc);
        if (ChestSelfTest.ENABLED) ChestSelfTest.tick(mc);
        if (UiSelfTest.ENABLED) UiSelfTest.tick(mc);
        if (++ticksSinceChange >= 40) saveNow();
        checkOverlaps();
    }

    /** True (and says so) when {@code what} is locked on the placement. */
    private static boolean refuse(LoadedPlacement lp, PlacementLock what) {
        if (!lp.locked(what)) return false;
        actionBar(lp.shortName() + ": " + what.label + " is locked (" + keyName("lock") + " or the schematic list unlocks it)");
        return true;
    }

    private static void selectNext() {
        if (PLACEMENTS.isEmpty()) return;
        int i = active == null ? -1 : PLACEMENTS.indexOf(active);
        active = PLACEMENTS.get((i + 1) % PLACEMENTS.size());
        actionBar("Selected " + active.shortName() + " (" + (PLACEMENTS.indexOf(active) + 1) + " of " + PLACEMENTS.size() + ")");
    }

    /** Asks BlockDesigner for its open project (BlockCompanion Plugin answers with it). */
    public static void grab() {
        if (!ClientLink.running()) {
            actionBar("The BlockDesigner link is off: start it on the schematic screen's BlockDesigner step");
            return;
        }
        if (ClientLink.grab()) actionBar("Asked BlockDesigner for its project...");
        else actionBar("BlockDesigner isn't connected: open the BlockCompanion Plugin there and connect to this game");
    }

    /** Development check of easy place, progress and the progress file; see {@link PlaceSelfTest}. */
    private static final boolean PLACE_SELF_TEST = "1".equals(System.getenv("BLOCKCOMPANION_PLACE_SELFTEST"));

    // ---- progress, effects and helpers ----------------------------------------------------------------------------

    private static void tickProgress(Minecraft mc) {
        String world = worldName(mc);
        for (LoadedPlacement lp : PLACEMENTS) {
            Path file = null;
            try {
                if (!lp.demo) file = library.resolve(lp.name());
            } catch (IOException e) {
                // Outside the library: no file to hash.
            }
            lp.progress.sync(lp.placement, file, saved == null || lp.demo ? null : saved.progressFile(lp.slot), world);
            if (here(lp)) {
                Box b = lp.placement.worldBox();
                double dx = Math.max(0, Math.max(b.minX() - mc.player.getX(), mc.player.getX() - b.maxX() - 1));
                double dy = Math.max(0, Math.max(b.minY() - mc.player.getY(), mc.player.getY() - b.maxY() - 1));
                double dz = Math.max(0, Math.max(b.minZ() - mc.player.getZ(), mc.player.getZ() - b.maxZ() - 1));
                lp.progress.tick(mc.level, true, dx * dx + dy * dy + dz * dz < 32 * 32, config.progressFile && !lp.demo);
            } else {
                lp.progress.tick(mc.level, false, false, config.progressFile && !lp.demo);
            }
        }
        EASY.tick(mc, shownHere());
        EFFECTS.tick(mc.level);
        refreshHelper(mc);
    }

    /** The world name for the progress file and the link: the singleplayer world folder, or the server address. */
    public static String worldName(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        }
        ServerData server = mc.getCurrentServer();
        return server != null && server.ip != null ? server.ip : "";
    }

    /** The held block's item id while the material helper runs, else null. */
    public static String helperItem() {
        return helperItem;
    }

    /** The material helper: while a block item is held, the nearest ghost cells that need it, over every shown placement. */
    private static void refreshHelper(Minecraft mc) {
        String item = null;
        List<LoadedPlacement> shown = shownHere();
        if (config.materialHelper && mc.player != null && !shown.isEmpty()) {
            net.minecraft.world.item.ItemStack held = mc.player.getMainHandItem();
            if (held.getItem() instanceof net.minecraft.world.item.BlockItem) {
                item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            }
        }
        if (item == null) {
            helperItem = null;
            for (LoadedPlacement lp : PLACEMENTS) lp.helperCells = List.of();
            return;
        }
        long version = 0;
        for (LoadedPlacement lp : shown) if (lp.progress.tracker() != null) version = version * 31 + lp.progress.tracker().version();
        if (!item.equals(helperItem) || version != helperVersion || ++helperTicks >= 20) {
            helperItem = item;
            helperVersion = version;
            helperTicks = 0;
            for (LoadedPlacement lp : PLACEMENTS) {
                ProgressTracker t = lp.progress.tracker();
                lp.helperCells = t == null || !shown.contains(lp) ? List.of()
                        : t.nearestMissing(item, mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), 48, config.materialHelperCells,
                        lp.layers::isVisible);
            }
        }
    }

    /** A single block changed in the world (any player's, or the client's own prediction). */
    public static void onBlockChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        boolean own = OWN.mine(pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
        for (LoadedPlacement lp : PLACEMENTS) {
            if (!here(lp) || !lp.placement.worldBox().contains(pos.getX(), pos.getY(), pos.getZ())) continue;
            lp.ghosts.onCellChanged(pos.getX(), pos.getY(), pos.getZ());
            ProgressTracker.Change c = lp.progress.onBlockChanged(pos.getX(), pos.getY(), pos.getZ(), state, own);
            // Counted even while hidden; the effects only play while the schematic shows.
            if (c == null || !lp.visible) continue;
            if (c.after() == ProgressTracker.Status.CORRECT) {
                // AutoBuild's blocks arrive several a second: no pop or sparkles for those, they cost frames.
                if (own || !io.blockcompanion.client.autobuild.AutoBuildClient.active(lp)) {
                    lp.ghosts.pop(c.x(), c.y(), c.z(), lp.placement.stateAt(c.x(), c.y(), c.z()));
                    EFFECTS.correct(mc.level, c.x(), c.y(), c.z());
                }
                ProgressTracker t = lp.progress.tracker();
                if (c.allCompleted() && !t.stats().finished) {
                    t.stats().finished = true;
                    EFFECTS.finished(mc.level, lp.placement.worldBox(), t.stats(), t.totals().total());
                } else if (c.levelCompleted() && lp.layers.isVisible(c.level())) {
                    EFFECTS.levelDone(mc.level, lp.placement.worldBox(), c.level());
                    if (config.autoAdvanceLayer && !lp.layers.showsAll() && c.level() == lp.layers.level() && !lp.locked(PlacementLock.LAYERS)) {
                        lp.layers.step(1, lp.placement.localSizeY());
                        changed(lp);
                        actionBar(lp.layers.showsAll() ? "All layers" : "On to layer " + (lp.layers.level() + 1));
                    }
                }
            } else if (c.after() == ProgressTracker.Status.WRONG && own) {
                // Only the player's own clicks: grass spreading, crops growing or a furnace lighting up stay quiet.
                io.blockcompanion.core.model.BlockState want = lp.placement.stateAt(c.x(), c.y(), c.z());
                if (!BuildProgress.halfway(want, StateMapper.toCore(state))) EFFECTS.wrong(mc.level, c.x(), c.y(), c.z());
            }
        }
    }

    /** Right-click, from the mixin: true when BlockCompanion handled it (the tool, or easy place). */
    public static boolean onUseItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        if (SelectionTool.onClear(mc)) return true;
        long now = System.currentTimeMillis();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            net.minecraft.core.BlockPos p = hit.getBlockPos();
            OWN.clicked(p.getX(), p.getY(), p.getZ(), now);
            net.minecraft.core.BlockPos q = p.relative(hit.getDirection());
            OWN.clicked(q.getX(), q.getY(), q.getZ(), now);
            ChestTracker.get().clicked(mc.level, p);
        }
        try {
            if (SelectionTool.onUse(mc)) return true;
            EasyPlace.Target t = EASY.target();
            if (t != null) OWN.clicked(t.pos().getX(), t.pos().getY(), t.pos().getZ(), now);
            return !PLACEMENTS.isEmpty() && EASY.onUse(mc, shownHere());
        } catch (RuntimeException e) {
            LOG.warn("Easy place failed", e);
            return false;
        }
    }

    /** Left click, from the mixin: true when the tool took it. */
    public static boolean onAttack() {
        return SelectionTool.onAttack(Minecraft.getInstance());
    }

    /** Held left button, from the mixin: true to stop mining (the tool aimed at a block). */
    public static boolean blocksMining() {
        return SelectionTool.blocksMining(Minecraft.getInstance());
    }

    /** Middle click, from the mixin: true when it picked a ghost's item. */
    public static boolean onPickBlock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || PLACEMENTS.isEmpty()) return false;
        try {
            return EASY.onPick(mc, shownHere());
        } catch (RuntimeException e) {
            LOG.warn("Pick block on a ghost failed", e);
            return false;
        }
    }

    /** The game is closing: write the progress files, save the state, close the link. */
    public static void onClientStopping() {
        // Closing the game inside the tutorial world: its schematic files are taken out of the library too.
        Tutorial.reset();
        saveNow();
        for (LoadedPlacement lp : PLACEMENTS) lp.progress.close();
        ChestTracker.get().save();
        ClientLink.stop();
    }

    public static EasyPlace easyPlace() {
        return EASY;
    }

    /** Easy place's auto mode clicked a cell: the change there is the player's own (effects, wrong-block note). */
    public static void noteOwnClick(net.minecraft.core.BlockPos p) {
        OWN.clicked(p.getX(), p.getY(), p.getZ(), System.currentTimeMillis());
    }

    /**
     * Development check: with {@code BLOCKCOMPANION_FORMATS_SELFTEST=1}, a few seconds after joining a world the client
     * saves a 7x5x7 box around the player as {@code blockcompanion-selftest.schem} (through the same path as the save
     * screen), then loads every .bdproj and .schem in the library and logs what it found. The placements are not touched.
     */
    private static final boolean FORMATS_SELF_TEST = "1".equals(System.getenv("BLOCKCOMPANION_FORMATS_SELFTEST"));
    private static int selfTestTicks;

    private static void formatsSelfTest(Minecraft mc) {
        selfTestTicks++;
        if (selfTestTicks == 100) {
            net.minecraft.core.BlockPos p = mc.player.blockPosition();
            String dim = dimensionId(mc.level);
            SELECTION.mark(new BlockPos(p.getX() - 3, p.getY() - 2, p.getZ() - 3), dim);
            SELECTION.mark(new BlockPos(p.getX() + 3, p.getY() + 2, p.getZ() + 3), dim);
            SaveRegion r = saveRegion();
            // A chest with diamonds in the box for the length of the test, to check block entities come along.
            net.minecraft.client.server.IntegratedServer server = mc.getSingleplayerServer();
            net.minecraft.core.BlockPos chestPos = p.offset(2, 0, 0);
            if (server != null) {
                var key = mc.level.dimension();
                server.execute(() -> {
                    var level = server.getLevel(key);
                    if (level == null) return;
                    level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    if (level.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
                    }
                });
            }
            LOG.info("Formats self-test: saving {} from {}", r.box(), r.source());
            RegionSaver.save(r.box(), "blockcompanion-selftest", true);
            if (server != null) {
                var key = mc.level.dimension();
                server.execute(() -> {
                    var level = server.getLevel(key);
                    if (level != null) level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                });
            }
        } else if (selfTestTicks == 200) {
            SELECTION.clear();
            try {
                for (SchematicLibrary.Entry e : library.list()) {
                    if (!e.preferred()) continue;
                    Structure s = library.load(e.name());
                    LOG.info("Formats self-test: {} ({}) loads: {} blocks, {} block entities, {} entities, box {}", e.name(), e.kind().label(),
                            s.blockCount(), s.blockEntities().size(), s.entities().size(), s.bounds().orElse(null));
                    if (e.name().equals("blockcompanion-selftest.schem")) {
                        s.blockEntities().forEach((pos, nbt) -> LOG.info("Formats self-test: block entity at {}: {}", pos, nbt));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Formats self-test failed", e);
            }
        }
    }

    /** Marks a save corner at the block looked at (or the block the player stands in). */
    private static void markCorner(Minecraft mc) {
        net.minecraft.core.BlockPos p;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) p = hit.getBlockPos();
        else p = mc.player.blockPosition();
        BlockPos pos = new BlockPos(p.getX(), p.getY(), p.getZ());
        int which = SELECTION.mark(pos, dimensionId(mc.level));
        if (which == 1) {
            actionBar("Corner 1 at " + pos.x() + ", " + pos.y() + ", " + pos.z() + ": now mark the opposite corner");
        } else {
            Box b = SELECTION.box().orElseThrow();
            actionBar("Corner 2 at " + pos.x() + ", " + pos.y() + ", " + pos.z() + ": " + b.sizeX() + " × " + b.sizeY() + " × " + b.sizeZ()
                    + ", press " + keyName("save") + " to save");
        }
    }

    private static void stepLayer(int dir) {
        LoadedPlacement f = focus();
        if (f == null || refuse(f, PlacementLock.LAYERS)) return;
        f.layers.step(dir, f.placement.localSizeY());
        changed(f);
        if (f.layers.showsAll()) actionBar("All layers");
    }

    private static void onLevelChanged(Minecraft mc) {
        lastLevel = mc.level;
        Tutorial.reset();
        SELECTION.clear();
        SELECTION_RENDER.clear();
        OWN.clear();
        ClientLink.statusChanged();
        if (mc.level == null) {
            saveNow();
            unloadAll(false);
            ChestTracker.get().open(null);
            worldKey = null;
            saved = null;
            return;
        }
        String key = worldKey(mc);
        // Another dimension of the same world: everything stays loaded, and each shows in its own dimension.
        if (key.equals(worldKey)) return;
        saveNow();
        unloadAll(false);
        worldKey = key;
        saved = new SavedPlacements(placementsDir, key);
        ChestTracker.get().open(saved.folder().resolve("chests.json"));
        restore();
        welcomeTicks = 0;
    }

    private static String worldKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            Path dir = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
            return "sp_" + SavedPlacement.safeKey(dir.getFileName().toString());
        }
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null) return "mp_" + SavedPlacement.safeKey(server.ip);
        return "mp_unknown";
    }

    // ---- loading, moving, saving ----------------------------------------------------------------------------------

    /** Loads a schematic from the library as a new placement just in front of the player, and selects it. */
    public static boolean load(String name) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;
        Structure s = read(name);
        if (s == null) return false;
        Placement p = new Placement(name, s, BlockPos.ORIGIN);
        p.moveTo(inFrontOf(player, p.worldBox()));
        LoadedPlacement lp = add(p, dimensionId(mc.level));
        lp.layers.set(-1, Layers.Mode.BUILD_UP);
        changed(lp);
        actionBar("Loaded " + lp.shortName() + " (" + s.blockCount() + " blocks)" + (PLACEMENTS.size() > 1 ? ", " + PLACEMENTS.size() + " loaded" : ""));
        return true;
    }

    /** Loads the tour's demo schematic where it is, and selects it; it is never saved (see {@link LoadedPlacement#demo}). */
    public static LoadedPlacement load(Placement p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        LoadedPlacement lp = add(p, dimensionId(mc.level));
        lp.demo = true;
        lp.layers.set(-1, Layers.Mode.BUILD_UP);
        changed(lp, false);
        return lp;
    }

    private static Structure read(String name) {
        Structure s;
        try {
            s = library.load(name);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not load schematic {}", name, e);
            actionBar("Could not load " + name + ": " + e.getMessage());
            return null;
        }
        if (s.blockCount() == 0) {
            actionBar(name + " has no blocks");
            return null;
        }
        return s;
    }

    private static LoadedPlacement add(Placement p, String dimension) {
        List<Integer> used = new ArrayList<>();
        for (LoadedPlacement lp : PLACEMENTS) used.add(lp.slot);
        LoadedPlacement lp = new LoadedPlacement(SavedPlacements.freeSlot(used), p, dimension);
        PLACEMENTS.add(lp);
        active = lp;
        ClientLink.statusChanged();
        return lp;
    }

    /** Moves a placement to just in front of the player (in this dimension only). */
    public static void bringHere(LoadedPlacement lp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !here(lp) || refuse(lp, PlacementLock.POSITION)) return;
        lp.placement.moveTo(inFrontOf(mc.player, lp.placement.worldBox()));
        changed(lp);
    }

    /**
     * Swaps a placement's schematic for another file (a new version from BlockDesigner or the server), keeping where it
     * is, how it's turned and its layer view. Returns null on success, else why not.
     */
    public static String reload(LoadedPlacement lp, String newName) {
        Structure s = read(newName);
        if (s == null) return "Could not load " + newName;
        Placement p = new Placement(newName, s, BlockPos.ORIGIN);
        p.setOrientation(lp.placement.rotation(), lp.placement.mirrored());
        p.moveTo(lp.placement.origin());
        lp.progress.close();
        lp.ghosts.clear();
        lp.placement = p;
        lp.layers.clampTo(p.localSizeY());
        changed(lp);
        return null;
    }

    /** Box origin that puts a box of this size two blocks in front of the player, centred on where they face. */
    private static BlockPos inFrontOf(LocalPlayer player, Box box) {
        int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
        int sx = box.sizeX(), sz = box.sizeZ();
        Direction facing = player.getDirection();
        return switch (facing) {
            case NORTH -> new BlockPos(px - sx / 2, py, pz - 2 - (sz - 1));
            case SOUTH -> new BlockPos(px - sx / 2, py, pz + 2);
            case WEST -> new BlockPos(px - 2 - (sx - 1), py, pz - sz / 2);
            default -> new BlockPos(px + 2, py, pz - sz / 2);
        };
    }

    /** Removes a placement (and, when {@code forget}, its saved slot for this world). */
    public static void unload(LoadedPlacement lp, boolean forget) {
        if (!PLACEMENTS.remove(lp)) return;
        if (forget && saved != null) {
            try {
                lp.progress.close();
                saved.delete(lp.slot);
            } catch (IOException e) {
                LOG.warn("Could not delete saved placement: {}", e.toString());
            }
        } else {
            saveSlot(lp);
            lp.progress.close();
        }
        lp.ghosts.clear();
        if (lp == hovered) {
            hovered = null;
            hover = null;
        }
        if (lp == sharedLink) sharedLink = null;
        UNDO.forget(lp);
        if (lp == soloOwner) endSolo();
        SOLO_HIDDEN.remove(lp);
        if (lp == active) active = PLACEMENTS.isEmpty() ? null : PLACEMENTS.get(PLACEMENTS.size() - 1);
        if (PLACEMENTS.isEmpty()) {
            EFFECTS.clear();
            EASY.clear();
        }
        ClientLink.statusChanged();
    }

    /** Unloads the selected placement (for the self-test and old callers). */
    public static void unload(boolean forget) {
        if (active != null) unload(active, forget);
    }

    private static void unloadAll(boolean forget) {
        for (LoadedPlacement lp : List.copyOf(PLACEMENTS)) unload(lp, forget);
        active = null;
    }

    private static void restore() {
        for (SavedPlacements.Slot slot : saved.list()) {
            SavedPlacement sp = slot.placement();
            try {
                Placement p = new Placement(sp.file(), library.load(sp.file()), sp.origin());
                p.setOrientation(sp.rotation(), sp.mirrored());
                LoadedPlacement lp = new LoadedPlacement(slot.slot(), p, sp.dimension());
                lp.layers.set(sp.level(), sp.mode());
                lp.layers.clampTo(p.localSizeY());
                lp.locks.addAll(sp.locks());
                lp.visible = sp.visible();
                lp.live = sp.live();
                lp.baseline = lp.state();
                PLACEMENTS.add(lp);
                active = lp;
                LOG.info("Restored placement of {} at {}", sp.file(), sp.origin());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not restore the saved placement of {}: {}", sp.file(), e.toString());
            }
        }
        ClientLink.statusChanged();
    }

    /** Something about a placement or its layer view changed: save it soon, and record it for undo. */
    public static void changed(LoadedPlacement lp) {
        changed(lp, true);
    }

    /**
     * Something about a placement changed: save it soon. With {@code record}, the change (against its state after the last
     * one) becomes an undo step and the placement what undo acts on; without, it is taken as the new starting point (a move
     * someone else made on the server, an undo itself).
     */
    public static void changed(LoadedPlacement lp, boolean record) {
        PlacementHistory.State now = lp.state();
        if (record && lp.baseline != null && !now.equals(lp.baseline)) {
            UNDO.record(lp, lp.baseline, now, System.currentTimeMillis());
        }
        lp.baseline = now;
        lp.dirty = true;
        ticksSinceChange = 0;
        ClientLink.statusChanged();
    }

    /** The selected placement changed (for old callers). */
    public static void changed() {
        if (active != null) changed(active);
    }

    private static void saveNow() {
        for (LoadedPlacement lp : PLACEMENTS) if (lp.dirty) saveSlot(lp);
    }

    private static void saveSlot(LoadedPlacement lp) {
        lp.dirty = false;
        if (saved == null || lp.demo) return;
        try {
            saved.write(lp.slot, lp.saved());
        } catch (IOException e) {
            LOG.warn("Could not save the placement: {}", e.toString());
        }
    }

    // ---- the link to BlockDesigner --------------------------------------------------------------------------------

    /**
     * A project from BlockDesigner was saved in the library: placements loaded from it that follow BlockDesigner switch
     * to the new version; when the user sent it (not a live update) and nothing shows it yet, it is loaded in front of
     * the player.
     */
    public static void onProjectReceived(String libraryName, boolean open, String app) {
        int updated = 0;
        for (LoadedPlacement lp : List.copyOf(PLACEMENTS)) {
            if (!lp.name().equalsIgnoreCase(libraryName) || (!lp.live && !open)) continue;
            String error = reload(lp, libraryName);
            if (error == null) updated++;
            else actionBar(error);
        }
        String shortName = libraryName.substring(libraryName.lastIndexOf('/') + 1);
        if (updated > 0) {
            actionBar("Updated " + shortName + " from " + app);
        } else if (open) {
            if (load(libraryName)) actionBar("Loaded " + shortName + " from " + app);
        }
    }

    /**
     * Edit in BlockDesigner: sends the placement's schematic file to the app, which opens it as its project and answers
     * with that project for this placement to follow ({@link #onProjectLinked}).
     */
    public static void editInBlockDesigner(LoadedPlacement lp) {
        if (ClientLink.state() != ClientLink.State.CONNECTED) {
            actionBar("BlockDesigner isn't connected: open the BlockCompanion Plugin there and connect to this game");
            return;
        }
        try {
            if (!ClientLink.edit(lp.slot, library.resolve(lp.name()), lp.shortName())) return;
        } catch (IOException e) {
            LOG.warn("Could not send {} to BlockDesigner", lp.name(), e);
            actionBar("Could not send " + lp.shortName() + " to BlockDesigner: " + e.getMessage());
            return;
        }
        EDITS.put(lp.slot, lp);
        actionBar("Opening " + lp.shortName() + " in BlockDesigner...");
    }

    /**
     * BlockDesigner answered an edit with its project, saved in the library: the placement that was sent switches to it
     * (keeping where it is, how it's turned, its locks and layer view) and follows BlockDesigner from now on. When that
     * placement is gone (unloaded, another world) it is handled like a project the user sent.
     */
    public static void onProjectLinked(String libraryName, int slot, String app) {
        LoadedPlacement lp = EDITS.remove(slot);
        if (lp == null || !PLACEMENTS.contains(lp)) {
            onProjectReceived(libraryName, true, app);
            return;
        }
        String error = reload(lp, libraryName);
        if (error != null) {
            actionBar(error);
            return;
        }
        lp.live = true;
        ClientLink.statusChanged();
        // Other placements already following that project get the new version too.
        for (LoadedPlacement other : List.copyOf(PLACEMENTS)) {
            if (other != lp && other.live && other.name().equalsIgnoreCase(libraryName)) reload(other, libraryName);
        }
        actionBar(lp.shortName() + " now follows BlockDesigner: changes made there show up here");
    }

    /** An app reported an error; about an edit when {@code slot} is one that was sent. */
    public static void onLinkError(String app, String message, int slot) {
        LoadedPlacement lp = slot < 0 ? null : EDITS.remove(slot);
        if (lp != null) actionBar(app + " couldn't open " + lp.shortName() + ": " + message);
        else actionBar(app + ": " + message);
    }

    /** The placement following a shared one on the server, or null. */
    public static LoadedPlacement sharedLink() {
        return sharedLink != null && PLACEMENTS.contains(sharedLink) ? sharedLink : null;
    }

    public static void setSharedLink(LoadedPlacement lp) {
        sharedLink = lp;
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    /**
     * Mouse wheel, from the mixin. Returns true to swallow the scroll (no hotbar change). Plain scrolling always changes
     * the hotbar slot; placements are only moved, turned and mirrored with the selection tool in hand
     * ({@code tool.requiredToMove}).
     *
     * <ul>
     *   <li>move modifier (Shift) + scroll while looking at a box: what the tool's mode says ({@link ToolMode}): moves it
     *   one block per notch along the axis of the face looked at (up pushes it away, down pulls it closer), or mirrors
     *   it;</li>
     *   <li>turn modifier (Ctrl) + scroll while looking at a placement's box: turns it 90 degrees (up = clockwise),
     *   whatever the mode;</li>
     *   <li>the save selection's box, when that is the one looked at (nearer than any placement's): only moves, both
     *   corners together;</li>
     *   <li>both modifiers (Ctrl+Shift) + scroll, tool in hand: switches the mode.</li>
     * </ul>
     */
    public static boolean onScroll(double yOffset) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null) return false;
        boolean holding = SelectionTool.holding(mc.player);
        boolean move = held(mc, config.moveModifier), turn = held(mc, config.rotateModifier);
        if (move && turn && config.moveModifier != config.rotateModifier) {
            if (!holding && !toolFree()) return false;
            int n = notches(yOffset);
            if (n != 0) {
                // Down steps forward, like the hotbar.
                config.toolMode = config.toolMode.next(-n);
                config.save(configFile);
                // The tool panel shows the new mode; without it, a short line above the hotbar does.
                if (!holding || !toolPanelShown()) actionBar("Tool: " + config.toolMode.label + " (" + config.moveModifier.label() + "+scroll)");
            }
            return true;
        }
        if (!move && !turn) return false;
        boolean onSelection = selectionHover != null, onPlacement = hovered != null && hover != null;
        if (!onSelection && !onPlacement) return false;
        if (!holding && !toolFree()) {
            hint("Hold the " + toolName() + " to " + (move ? config.toolMode.verb : "turn") + " it");
            return false;
        }
        if (onSelection) return scrollSelection(move && config.toolMode == ToolMode.MOVE, yOffset);
        return scrollAction(hovered, !move ? ScrollAction.TURN : config.toolMode == ToolMode.MIRROR ? ScrollAction.MIRROR : ScrollAction.MOVE, yOffset);
    }

    /** What one scroll does to a placement. */
    private enum ScrollAction {
        MOVE, TURN, MIRROR
    }

    /** Whether the tool panel is switched on (in the settings and in the HUD editor). */
    public static boolean toolPanelShown() {
        return config.toolHud && config.hud.get(io.blockcompanion.core.hud.HudLayout.Element.TOOL).enabled();
    }

    /** True when the save selection is the box looked at this frame (the move modifier + scroll moves it). */
    public static boolean selectionLookedAt() {
        return selectionHover != null;
    }

    /** One scroll's worth of {@code action} on a placement; always swallows the scroll. */
    private static boolean scrollAction(LoadedPlacement lp, ScrollAction action, double yOffset) {
        PlacementLock lock = switch (action) {
            case MOVE -> PlacementLock.POSITION;
            case TURN -> PlacementLock.ROTATION;
            case MIRROR -> PlacementLock.MIRROR;
        };
        if (refuse(lp, lock)) return true;
        int notches = notches(yOffset);
        if (notches == 0) return true;
        switch (action) {
            case MOVE -> {
                if (lp != hovered || hover == null) return true;
                // Push along the look direction into the face: opposite to the face's outward normal.
                BlockPos d = hover.push(notches);
                lp.placement.move(d.x(), d.y(), d.z());
                Box b = lp.placement.worldBox();
                actionBar("Moved to " + b.minX() + ", " + b.minY() + ", " + b.minZ());
            }
            case TURN -> {
                lp.placement.rotate(notches > 0 ? 1 : -1);
                actionBar("Rotated " + lp.placement.rotation() * 90 + "°");
            }
            case MIRROR -> {
                lp.placement.toggleMirror();
                actionBar(lp.placement.mirrored() ? "Mirrored" : "Not mirrored");
            }
        }
        active = lp;
        changed(lp);
        return true;
    }

    /**
     * One scroll on the save selection, looked at: with {@code move} (the move modifier in Move mode) it shifts both
     * corners like a placement (same direction, one block per notch) and is an undo step; turning and mirroring don't
     * apply to it. Always swallows the scroll.
     */
    private static boolean scrollSelection(boolean move, double yOffset) {
        int notches = notches(yOffset);
        if (notches == 0 || selectionHover == null) return true;
        if (!move) {
            hint("The selection can only be moved");
            return true;
        }
        PlacementHistory.State before = SELECTION.state();
        if (!SELECTION.move(selectionHover.push(notches))) return true;
        UNDO.record(SELECTION, before, SELECTION.state(), System.currentTimeMillis());
        Box b = SELECTION.box().orElseThrow();
        actionBar("Selection moved to " + b.minX() + ", " + b.minY() + ", " + b.minZ());
        return true;
    }

    /** Whole notches from the wheel (a touchpad sends fractions), keeping the rest for the next scroll. */
    private static int notches(double yOffset) {
        scrollRemainder += yOffset;
        int n = (int) scrollRemainder;
        scrollRemainder -= n;
        return n;
    }

    /** Whether modifier {@code m} is held down (never for {@code NONE}). */
    public static boolean held(Minecraft mc, ClientConfig.Modifier m) {
        if (m == ClientConfig.Modifier.NONE) return false;
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), m.left)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), m.right);
    }

    /** True when placements can be moved without the tool in hand (the setting is off, or there is no tool). */
    private static boolean toolFree() {
        return !config.toolRequired || config.toolItem == null || config.toolItem.isBlank();
    }

    /** True (and says so) when moving a placement needs the tool and it isn't in hand. */
    private static boolean needsTool(Minecraft mc, String what) {
        if (toolFree() || SelectionTool.holding(mc.player)) return false;
        actionBar("Hold the " + toolName() + " to " + what);
        return true;
    }

    /** The tool item's name, for messages. */
    public static String toolName() {
        try {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(config.toolItem));
            return new net.minecraft.world.item.ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return "selection tool";
        }
    }

    /** An action bar hint at most every few seconds, for things that happen on every scroll. */
    private static void hint(String message) {
        long now = System.currentTimeMillis();
        if (now - lastHint < 4000) return;
        lastHint = now;
        actionBar(message);
    }

    // ---- view cycle ------------------------------------------------------------------------------------------------

    /** The ways to show a placement that the view key steps through, most shown first. */
    private enum View {
        ALL("everything"),
        BUILD_UP("layers up to this one"),
        SINGLE("this layer only"),
        SOLO("only this schematic"),
        HIDDEN("hidden");

        final String label;

        View(String label) {
            this.label = label;
        }
    }

    private static View viewOf(LoadedPlacement lp) {
        if (!lp.visible) return View.HIDDEN;
        if (lp == soloOwner) return View.SOLO;
        if (lp.layers.showsAll()) return View.ALL;
        return lp.layers.mode() == Layers.Mode.SINGLE ? View.SINGLE : View.BUILD_UP;
    }

    /** Steps the placement's view one along ({@code dir} 1 hides more, -1 shows more), skipping what doesn't apply. */
    private static View cycleView(LoadedPlacement lp, int dir) {
        View[] all = View.values();
        View from = viewOf(lp), to = from;
        boolean others = false;
        for (LoadedPlacement o : shownHere()) if (o != lp) others = true;
        for (int i = 0; i < all.length; i++) {
            to = all[Math.floorMod(to.ordinal() + dir, all.length)];
            boolean layers = to == View.BUILD_UP || to == View.SINGLE;
            if (layers && lp.locked(PlacementLock.LAYERS)) continue;
            if (to == View.SOLO && !others && from != View.SOLO) continue;
            break;
        }
        if (to == from) return from;
        if (from == View.SOLO) endSolo();
        switch (to) {
            case ALL, SOLO -> {
                lp.visible = true;
                if (!lp.locked(PlacementLock.LAYERS)) lp.layers.showAll();
                if (to == View.SOLO) {
                    if (soloOwner != null && soloOwner != lp) endSolo();
                    soloOwner = lp;
                    for (LoadedPlacement o : shownHere()) {
                        if (o == lp) continue;
                        o.visible = false;
                        if (!SOLO_HIDDEN.contains(o)) SOLO_HIDDEN.add(o);
                        changed(o, false);
                    }
                }
            }
            case BUILD_UP, SINGLE -> {
                lp.visible = true;
                Layers.Mode mode = to == View.SINGLE ? Layers.Mode.SINGLE : Layers.Mode.BUILD_UP;
                int level = lp.layers.level();
                if (lp.layers.showsAll()) {
                    // Start at the level the player stands on.
                    LocalPlayer player = Minecraft.getInstance().player;
                    level = player == null ? 0 : player.getBlockY() - lp.placement.worldBox().minY();
                }
                lp.layers.set(Math.max(0, Math.min(lp.placement.localSizeY() - 1, level)), mode);
            }
            case HIDDEN -> lp.visible = false;
        }
        return to;
    }

    /** Ends "only this schematic": shows again the placements it hid. */
    private static void endSolo() {
        for (LoadedPlacement o : SOLO_HIDDEN) {
            if (!PLACEMENTS.contains(o) || o.visible) continue;
            o.visible = true;
            changed(o, false);
        }
        SOLO_HIDDEN.clear();
        soloOwner = null;
    }

    // ---- undo and redo --------------------------------------------------------------------------------------------

    /**
     * A key press in the world, from the mixin: Ctrl + the undo key undoes the last change to any placement (or the last
     * move of the selection), Ctrl + the
     * redo key or Ctrl+Shift + the undo key redoes it, like an ordinary program. Returns true when the key was taken.
     */
    public static boolean onUndoKey(boolean undoKey, boolean redoKey, boolean control, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null || !control || (!undoKey && !redoKey)) return false;
        undo(redoKey || shift);
        return true;
    }

    /** Undoes (or redoes) the last change, whichever placement it was on; says what happened. */
    public static void undo(boolean redo) {
        java.util.function.Function<Object, PlacementHistory.State> state = t -> t == SELECTION ? SELECTION.state() : ((LoadedPlacement) t).state();
        UndoTimeline.Result<Object> r = redo ? UNDO.redo(state) : UNDO.undo(state);
        if (r == null) {
            actionBar(redo ? "Nothing to redo" : "Nothing to undo");
            return;
        }
        PlacementHistory.Step step = r.step();
        if (r.target() == SELECTION) {
            // The selection has no locks: its moves always apply.
            SELECTION.apply(step.target());
            int left = redo ? UNDO.redoSize() : UNDO.undoSize();
            actionBar((redo ? "Redid " : "Undid ") + step.kind().label + " of the selection" + (left > 0 ? " (" + left + " more)" : ""));
            return;
        }
        LoadedPlacement lp = (LoadedPlacement) r.target();
        if (!step.applied()) {
            actionBar(lp.shortName() + ": can't " + (redo ? "redo" : "undo") + " the " + step.kind().label + ", " + step.blockedBy().label
                    + " is locked (" + keyName("lock") + " or the schematic list unlocks it)");
            return;
        }
        PlacementHistory.State t = step.target();
        if (lp == soloOwner && !t.visible()) endSolo();
        t.applyTo(lp.placement, lp.layers, lp.locks);
        lp.visible = t.visible();
        lp.layers.clampTo(lp.placement.localSizeY());
        // A plain change as far as saving and the server go (a linked placement's move is sent like any other).
        changed(lp, false);
        active = lp;
        int left = redo ? UNDO.redoSize() : UNDO.undoSize();
        actionBar((redo ? "Redid " : "Undid ") + step.kind().label + " of " + lp.shortName() + (left > 0 ? " (" + left + " more)" : ""));
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    /** World rendering: submits every shown placement's geometry for this frame. */
    public static void onSubmitWorld(LevelRenderState state, SubmitNodeCollector collector, PoseStack poseStack) {
        CameraRenderState camera = state.cameraRenderState;
        Minecraft mc = Minecraft.getInstance();
        // Boxes (selection and placements) show while the selection tool is held, or always, per the settings.
        boolean boxes = BoxRenderer.frame() > 0;
        Box selBox = boxes && !SELECTION.isEmpty() && mc.level != null && dimensionId(mc.level).equals(SELECTION.dimension())
                ? SELECTION.box().orElseThrow() : null;
        List<LoadedPlacement> shown = shownHere();
        for (LoadedPlacement lp : PLACEMENTS) if (!shown.contains(lp)) lp.ghosts.clear();
        if (camera == null || camera.pos == null) {
            hovered = null;
            hover = null;
            selectionHover = null;
            return;
        }
        Vec3 cam = camera.pos;
        Vec3 look = Vec3.directionFromRotation(camera.xRot, camera.yRot);
        // The looked-at box: the nearest one the view ray enters; standing inside, the selected one wins, else the smallest.
        // Hidden placements count while the boxes show (their box is drawn then), so the tool and the keys still reach them.
        List<LoadedPlacement> targets = boxes ? hereAll() : shown;
        LoadedPlacement best = null;
        RayBox.Hit bestHit = null;
        for (LoadedPlacement lp : targets) {
            RayBox.Hit h = RayBox.intersect(cam.x, cam.y, cam.z, look.x, look.y, look.z, lp.placement.worldBox(), config.reach);
            if (h == null) continue;
            if (best == null || better(h, lp, bestHit, best)) {
                best = lp;
                bestHit = h;
            }
        }
        hovered = best;
        hover = bestHit;
        // The selection is the box looked at when it is nearer than the placement's (see RayBox.before); a hidden placement
        // loses to it, as to a shown one. Only its box then gets the brighter face.
        RayBox.Hit selHit = selBox == null ? null : RayBox.intersect(cam.x, cam.y, cam.z, look.x, look.y, look.z, selBox, config.reach);
        selectionHover = selHit != null && (best == null || !best.visible || RayBox.before(selHit, selBox.volume(), bestHit, volume(best))) ? selHit : null;
        if (selBox != null) SELECTION_RENDER.submit(selBox, selectionHover != null, collector, poseStack, cam, look);
        EasyPlace.Target t = EASY.enabled() ? EASY.target() : null;
        for (LoadedPlacement lp : shown) {
            lp.ghosts.setTarget(t != null && t.owner() == lp ? t.pos() : null);
            lp.ghosts.setHighlights(lp.helperCells);
            lp.ghosts.submit(lp.placement, lp.layers, collector, poseStack, cam, camera.cullFrustum, camera);
            if (boxes) submitBox(lp, cam, look, collector, poseStack);
        }
        if (boxes) for (LoadedPlacement lp : targets) if (!lp.visible) submitBox(lp, cam, look, collector, poseStack);
    }

    /** A placement's box: locked ones stay blue, then looked at, selected, and the rest faint grey. */
    private static void submitBox(LoadedPlacement lp, Vec3 cam, Vec3 look, SubmitNodeCollector collector, PoseStack poseStack) {
        Palette colors = config.colors;
        boolean locked = lp.locks.containsAll(PlacementLock.IN_PLACE);
        // Looking at the selection in front of it, the placement isn't the box looked at.
        boolean looked = lp == hovered && selectionHover == null;
        int rgb = locked ? colors.get(Palette.Entry.BOX_LOCKED)
                : looked ? colors.get(Palette.Entry.BOX_HOVER)
                : lp == active ? colors.get(Palette.Entry.BOX) : BoxLook.OTHER_RGB;
        double alpha = locked || looked || lp == active ? BoxLook.EDGE_ALPHA : BoxLook.EDGE_ALPHA_OTHER;
        int face = looked && hover != null ? BoxLook.face(hover.axis(), hover.sign()) : -1;
        BoxRenderer.submit(lp.placement.worldBox(), rgb, alpha, face, looked, collector, poseStack, cam);
    }

    private static boolean better(RayBox.Hit h, LoadedPlacement lp, RayBox.Hit other, LoadedPlacement otherLp) {
        // A shown placement wins over a hidden one.
        if (lp.visible != otherLp.visible) return lp.visible;
        if (h.inside() != other.inside()) return !h.inside();
        if (!h.inside()) return h.distance() < other.distance();
        if (lp == active) return true;
        if (otherLp == active) return false;
        return volume(lp) < volume(otherLp);
    }

    private static long volume(LoadedPlacement lp) {
        Box b = lp.placement.worldBox();
        return (long) b.sizeX() * b.sizeY() * b.sizeZ();
    }

    /** From the mixin: the game re-meshes a world section, so ours there is stale too. */
    public static void onWorldSectionDirty(int sx, int sy, int sz) {
        for (LoadedPlacement lp : PLACEMENTS) {
            lp.ghosts.onWorldSectionDirty(sx, sy, sz);
            lp.progress.onSectionDirty(sx, sy, sz);
        }
    }

    /** From the mixin: every chunk is re-meshed (resource packs changed, F3+T): rebuild the ghosts with the new models. */
    public static void onAllChanged() {
        for (LoadedPlacement lp : PLACEMENTS) lp.ghosts.clear();
        ClientLink.statusChanged();
    }

    /** The HUD: the info panel and the crosshair hint, where the HUD editor put them. */
    public static void onRenderHud(GuiGraphicsExtractor g) {
        Hud.render(g, EASY);
        Tutorial.render(g);
    }

    public static void actionBar(String message) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendOverlayMessage(Component.literal(message));
    }

    // ---- overlapping placements ------------------------------------------------------------------------------------

    /** Pairs of placements (by slot) already warned about, until they stop overlapping. */
    private static final java.util.Set<Long> OVERLAP_WARNED = new java.util.HashSet<>();
    private static int overlapTicks;

    /**
     * Every 2 seconds: two shown placements in this dimension that sit mostly on top of each other (at least half of the
     * smaller one) are usually one loaded twice. Both are drawn, counted and built, which costs frames and has AutoBuild
     * build over itself, so say so once in chat. Warned again only after they have been apart.
     */
    private static void checkOverlaps() {
        if (++overlapTicks < 40) return;
        overlapTicks = 0;
        java.util.Set<Long> now = new java.util.HashSet<>();
        for (int i = 0; i < PLACEMENTS.size(); i++) {
            LoadedPlacement a = PLACEMENTS.get(i);
            if (!a.visible || !here(a)) continue;
            Box ab = a.placement.worldBox();
            for (int j = i + 1; j < PLACEMENTS.size(); j++) {
                LoadedPlacement b = PLACEMENTS.get(j);
                if (!b.visible || !here(b) || !a.dimension.equals(b.dimension)) continue;
                Box bb = b.placement.worldBox();
                if (ab.overlap(bb) * 2 < Math.min(ab.volume(), bb.volume())) continue;
                long pair = ((long) Math.min(a.slot, b.slot) << 32) | Math.max(a.slot, b.slot);
                now.add(pair);
                if (OVERLAP_WARNED.add(pair)) {
                    String what = a.name().equals(b.name()) ? a.shortName() + " is loaded twice in the same place"
                            : a.shortName() + " and " + b.shortName() + " are on top of each other";
                    chat(what + ": both are drawn and built, which costs frames. Move or unload one (" + keyName("library") + ").");
                }
            }
        }
        OVERLAP_WARNED.retainAll(now);
    }

    /** A few seconds into a world: a line in chat naming the menu's key, and the guide the first time ever. */
    private static void welcome(Minecraft mc) {
        if (welcomeTicks < 0 || ++welcomeTicks < 60 || mc.gui.screen() != null) return;
        welcomeTicks = -1;
        chat("Press " + keyName("library") + " to open the BlockCompanion menu. Its settings have a guide to the mod.");
        if (config.guideSeen || PLACE_SELF_TEST || ChestSelfTest.ENABLED || UiSelfTest.ENABLED) return;
        config.guideSeen = true;
        configChanged();
        mc.gui.setScreen(new GuideScreen(null));
    }

    /** A line in chat, marked as BlockCompanion's. */
    private static void chat(String message) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendSystemMessage(line(message));
    }

    private static Component line(String message) {
        return Component.literal("[BlockCompanion] ").withStyle(net.minecraft.ChatFormatting.DARK_AQUA)
                .append(Component.literal(message).withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
