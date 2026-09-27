package io.blockcompanion.client;

import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.easyplace.EasyPlace;
import io.blockcompanion.client.fx.Effects;
import io.blockcompanion.client.hud.Hud;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.client.progress.BuildProgress;
import io.blockcompanion.client.render.GhostRenderer;
import io.blockcompanion.client.render.SelectionRenderer;
import io.blockcompanion.client.screen.LibraryScreen;
import io.blockcompanion.client.screen.ResourceScreen;
import io.blockcompanion.client.screen.SaveScreen;
import io.blockcompanion.client.screen.SettingsScreen;
import io.blockcompanion.client.tool.SelectionTool;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.placement.RayBox;
import io.blockcompanion.core.placement.SavedPlacement;
import io.blockcompanion.core.placement.SavedPlacements;
import io.blockcompanion.core.placement.Selection;
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
import java.util.List;

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
    private static Path modJar;
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
    /** The placement whose box the player's view ray meets this frame, and where; null when none. */
    private static LoadedPlacement hovered;
    private static RayBox.Hit hover;

    private static Level lastLevel;
    private static String worldKey;
    private static SavedPlacements saved;
    private static int ticksSinceChange;
    private static double scrollRemainder;

    private BlockCompanionClient() {
    }

    /**
     * The platform's name and the mod's version, for the link's instance file and update checks, and the mod's jar
     * (null in a development run), which an update replaces; call before {@link #init()}.
     */
    public static void setPlatform(String loaderName, String version, Path jar) {
        loader = loaderName;
        modVersion = version;
        modJar = jar;
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
        Updates.init(loader, modVersion, modJar);
        LOG.info("BlockCompanion ready; schematics in {}", library.root());
    }

    public static ClientConfig config() {
        return config;
    }

    /** Saves the config after a change on the settings screen, and applies what needs more than a new value. */
    public static void configChanged() {
        config.save(configFile);
        for (LoadedPlacement lp : PLACEMENTS) lp.ghosts.invalidate();
        if (config.link && !ClientLink.running()) ClientLink.start(library.root(), loader, modVersion);
        else if (!config.link && ClientLink.running()) ClientLink.stop();
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
        Updates.tick(mc);
        if (mc.level == null || mc.player == null) return;

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
        while (Keys.LOCK.consumeClick()) {
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
            if (f == null || refuse(f, PlacementLock.MIRROR)) continue;
            f.placement.toggleMirror();
            changed(f);
            actionBar(f.placement.mirrored() ? "Mirrored" : "Not mirrored");
        }
        while (Keys.LAYER_UP.consumeClick()) stepLayer(1);
        while (Keys.LAYER_DOWN.consumeClick()) stepLayer(-1);
        while (Keys.EASY_PLACE.consumeClick()) {
            config.easyPlace = !config.easyPlace;
            config.save(configFile);
            if (config.easyPlace && !EasyPlace.serverAllows()) actionBar("Easy place is switched off on this server");
            else actionBar(config.easyPlace ? "Easy place on: right-click a ghost with its block" : "Easy place off");
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
        if (++ticksSinceChange >= 40) saveNow();
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

    /** Asks BlockDesigner for its open project (Resource Tracker answers with it). */
    public static void grab() {
        if (!ClientLink.running()) {
            actionBar("The BlockDesigner link is off (Settings)");
            return;
        }
        if (ClientLink.grab()) actionBar("Asked BlockDesigner for its project...");
        else actionBar("BlockDesigner isn't connected: open Resource Tracker's Game link there and connect to this game");
    }

    /** Development check of easy place, progress and the progress file; see {@link PlaceSelfTest}. */
    private static final boolean PLACE_SELF_TEST = "1".equals(System.getenv("BLOCKCOMPANION_PLACE_SELFTEST"));

    // ---- progress, effects and helpers ----------------------------------------------------------------------------

    private static void tickProgress(Minecraft mc) {
        String world = worldName(mc);
        for (LoadedPlacement lp : PLACEMENTS) {
            Path file = null;
            try {
                file = library.resolve(lp.name());
            } catch (IOException e) {
                // Outside the library: no file to hash.
            }
            lp.progress.sync(lp.placement, file, saved == null ? null : saved.progressFile(lp.slot), world);
            if (here(lp)) {
                Box b = lp.placement.worldBox();
                double dx = Math.max(0, Math.max(b.minX() - mc.player.getX(), mc.player.getX() - b.maxX() - 1));
                double dy = Math.max(0, Math.max(b.minY() - mc.player.getY(), mc.player.getY() - b.maxY() - 1));
                double dz = Math.max(0, Math.max(b.minZ() - mc.player.getZ(), mc.player.getZ() - b.maxZ() - 1));
                lp.progress.tick(mc.level, true, dx * dx + dy * dy + dz * dz < 32 * 32, config.progressFile);
            } else {
                lp.progress.tick(mc.level, false, false, config.progressFile);
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
            ProgressTracker.Change c = lp.progress.onBlockChanged(pos.getX(), pos.getY(), pos.getZ(), state, own);
            // Counted even while hidden; the effects only play while the schematic shows.
            if (c == null || !lp.visible) continue;
            if (c.after() == ProgressTracker.Status.CORRECT) {
                lp.ghosts.pop(c.x(), c.y(), c.z(), lp.placement.stateAt(c.x(), c.y(), c.z()));
                EFFECTS.correct(mc.level, c.x(), c.y(), c.z());
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
        saveNow();
        for (LoadedPlacement lp : PLACEMENTS) lp.progress.close();
        ChestTracker.get().save();
        ClientLink.stop();
    }

    public static EasyPlace easyPlace() {
        return EASY;
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
                PLACEMENTS.add(lp);
                active = lp;
                LOG.info("Restored placement of {} at {}", sp.file(), sp.origin());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not restore the saved placement of {}: {}", sp.file(), e.toString());
            }
        }
        ClientLink.statusChanged();
    }

    /** Something about a placement or its layer view changed: save it soon. */
    public static void changed(LoadedPlacement lp) {
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
        if (saved == null) return;
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

    /** The placement following a shared one on the server, or null. */
    public static LoadedPlacement sharedLink() {
        return sharedLink != null && PLACEMENTS.contains(sharedLink) ? sharedLink : null;
    }

    public static void setSharedLink(LoadedPlacement lp) {
        sharedLink = lp;
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    /**
     * Mouse wheel, from the mixin. With the move modifier held while looking at a placement's box, moves it one block
     * per notch along the axis of the face looked at (scrolling up pushes it away, down pulls it closer); with the
     * rotate modifier, turns it 90 degrees (up = clockwise). Returns true to swallow the scroll (no hotbar change).
     */
    public static boolean onScroll(double yOffset) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || hovered == null || hover == null) return false;
        boolean move = held(mc, config.moveModifier), rotate = held(mc, config.rotateModifier);
        if (!move && !rotate) return false;
        LoadedPlacement lp = hovered;
        if (refuse(lp, move ? PlacementLock.POSITION : PlacementLock.ROTATION)) return true;
        scrollRemainder += yOffset;
        int notches = (int) scrollRemainder;
        scrollRemainder -= notches;
        if (notches == 0) return true;
        if (move) {
            // Push along the look direction into the face: opposite to the face's outward normal.
            int dir = hover.inside() ? hover.sign() : -hover.sign();
            int d = dir * notches;
            lp.placement.move(hover.axis() == 0 ? d : 0, hover.axis() == 1 ? d : 0, hover.axis() == 2 ? d : 0);
            Box b = lp.placement.worldBox();
            actionBar("Moved to " + b.minX() + ", " + b.minY() + ", " + b.minZ());
        } else {
            lp.placement.rotate(notches > 0 ? 1 : -1);
            actionBar("Rotated " + lp.placement.rotation() * 90 + "°");
        }
        active = lp;
        changed(lp);
        return true;
    }

    private static boolean held(Minecraft mc, ClientConfig.Modifier m) {
        if (m == ClientConfig.Modifier.NONE) return false;
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(m.left)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(m.right);
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    /** Box outline colours: looked at, selected, other, and locked in place. */
    private static final int BOX_OTHER = 0x80C8C8C8;

    /** World rendering: submits every shown placement's geometry for this frame. */
    public static void onSubmitWorld(LevelRenderState state, SubmitNodeCollector collector, PoseStack poseStack) {
        CameraRenderState camera = state.cameraRenderState;
        Minecraft mc = Minecraft.getInstance();
        if (!SELECTION.isEmpty() && camera != null && camera.pos != null && mc.level != null && dimensionId(mc.level).equals(SELECTION.dimension())) {
            SELECTION_RENDER.submit(SELECTION.box().orElseThrow(), collector, poseStack, camera.pos);
        }
        List<LoadedPlacement> shown = shownHere();
        for (LoadedPlacement lp : PLACEMENTS) if (!shown.contains(lp)) lp.ghosts.clear();
        if (camera == null || camera.pos == null) {
            hovered = null;
            hover = null;
            return;
        }
        Vec3 cam = camera.pos;
        Vec3 look = Vec3.directionFromRotation(camera.xRot, camera.yRot);
        // The looked-at box: the nearest one the view ray enters; standing inside, the selected one wins, else the smallest.
        LoadedPlacement best = null;
        RayBox.Hit bestHit = null;
        for (LoadedPlacement lp : shown) {
            RayBox.Hit h = RayBox.intersect(cam.x, cam.y, cam.z, look.x, look.y, look.z, lp.placement.worldBox(), config.reach);
            if (h == null) continue;
            if (best == null || better(h, lp, bestHit, best)) {
                best = lp;
                bestHit = h;
            }
        }
        hovered = best;
        hover = bestHit;
        EasyPlace.Target t = EASY.enabled() ? EASY.target() : null;
        for (LoadedPlacement lp : shown) {
            lp.ghosts.setTarget(t != null && t.owner() == lp ? t.pos() : null);
            lp.ghosts.setHighlights(lp.helperCells);
            Palette colors = config.colors;
            int color = lp == hovered ? colors.argb(Palette.Entry.BOX_HOVER, 0xFF)
                    : lp.locks.containsAll(PlacementLock.IN_PLACE) ? colors.argb(Palette.Entry.BOX_LOCKED, 0xCC)
                    : lp == active ? colors.argb(Palette.Entry.BOX, 0xCC) : BOX_OTHER;
            lp.ghosts.submit(lp.placement, lp.layers, color, lp == hovered, collector, poseStack, cam, camera.cullFrustum, camera);
        }
    }

    private static boolean better(RayBox.Hit h, LoadedPlacement lp, RayBox.Hit other, LoadedPlacement otherLp) {
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
    }

    public static void actionBar(String message) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.sendOverlayMessage(Component.literal(message));
    }
}
