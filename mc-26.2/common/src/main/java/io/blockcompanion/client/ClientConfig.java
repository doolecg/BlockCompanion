package io.blockcompanion.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.core.hud.HudLayout;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.placement.ToolMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * {@code config/blockcompanion.properties}: the settings that are not key bindings (those are in Options > Controls).
 * Every one of them is also on the in-game settings screen, which applies changes straight away and saves them.
 */
public final class ClientConfig {
    /** Bumped when a default changes enough that old files should pick up the new value. */
    private static final int VERSION = 3;

    /** A modifier key held while scrolling or clicking. */
    public enum Modifier {
        ALT(InputConstants.KEY_LALT, InputConstants.KEY_RALT),
        CTRL(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL),
        SHIFT(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT),
        /** Disables that scroll action. */
        NONE(-1, -1);

        public final int left, right;

        Modifier(int left, int right) {
            this.left = left;
            this.right = right;
        }

        /** "Alt", "Ctrl", "Shift" or "Off", for messages and the settings screen. */
        public String label() {
            return this == NONE ? "Off" : name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }

        static Modifier parse(String s, Modifier fallback) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                return fallback;
            }
        }
    }

    /**
     * Held while scrolling and looking at a box: does the tool's mode (moves it, or mirrors it). Held together with
     * {@link #rotateModifier} (Ctrl+Shift by default), switches the mode instead.
     */
    public Modifier moveModifier = Modifier.SHIFT;
    /** Held while scrolling and looking at a placement's box: turns it 90 degrees around Y, whatever the mode. */
    public Modifier rotateModifier = Modifier.CTRL;
    /** Held while right-clicking with the selection tool, clears the selection. */
    public Modifier clearModifier = Modifier.SHIFT;
    /** Held while clicking with the selection tool: left-click sets corner 1, right-click corner 2. NONE: keys only. */
    public Modifier cornerModifier = Modifier.ALT;
    /** Held while right-clicking a chest with the selection tool: links it, or unlinks it. */
    public Modifier linkModifier = Modifier.CTRL;
    /** What the move modifier + scroll does with the selection tool in hand while looking at a box. */
    public ToolMode toolMode = ToolMode.MOVE;
    /**
     * Moving, turning and mirroring a placement in the world (Shift / Ctrl + scroll, the mirror key) only work with the
     * selection tool in hand; without it the scroll wheel changes the hotbar slot as usual. Ignored while the tool is off.
     */
    public boolean toolRequired = true;
    /** How far away (blocks) looking at the placement's box still counts. */
    public double reach = 96;

    /** Opacity of missing-block ghosts, 0.3 to 1. */
    public float ghostAlpha = 0.85f;
    /** A slow, gentle pulse and a cool tint that mark ghosts as not built yet. */
    public boolean ghostShimmer = true;
    /** Chests, signs, beds, banners, heads... drawn with their real shapes. */
    public boolean ghostBlockEntities = true;
    /**
     * Ghosts are drawn (and meshed) only this far from the player, in blocks; 0 draws all of them. Every ghost in view
     * is drawn each frame, so a big schematic seen whole costs frames; progress is still followed everywhere.
     */
    public int ghostDistance = 64;
    /** Easy place, auto place, picking and targeting only act on blocks this far from the player, in blocks; 0 means no limit. */
    public int interactRange = 0;

    /** Right-click on a ghost with its item places exactly that block. Can be toggled with its key. */
    public boolean easyPlace = true;
    /** Easy place brings the ghost's block from the inventory into the hand when it isn't held. */
    public boolean easyPlaceAutoPick = true;
    /** Auto place: the missing blocks within reach place themselves, from the blocks the player carries. Has its own key. */
    public boolean easyPlaceAuto = false;
    /** Most blocks a second auto place places (1 to 20). */
    public int easyPlaceAutoRate = io.blockcompanion.core.easyplace.AutoPlacePlanner.DEFAULT_RATE;
    /** How many items one fetch from the linked chests asks for. */
    public int restockCount = 64;
    /** AutoBuild's speed in blocks per second (the server may cap it). The AutoBuild options start from these defaults. */
    public int autoBuildSpeed = 5;
    /** What AutoBuild may break to put the schematic's block in place (the server may allow less). */
    public io.blockcompanion.core.autobuild.AutoBuildOptions.Replace autoBuildReplace = io.blockcompanion.core.autobuild.AutoBuildOptions.Replace.KEEP;
    /** Which blocks AutoBuild places first. */
    public io.blockcompanion.core.autobuild.AutoBuildOptions.Order autoBuildOrder = io.blockcompanion.core.autobuild.AutoBuildOptions.Order.BOTTOM_UP;
    /** The schematic's air is never touched; off (with Replace and clear) AutoBuild clears blocks where the schematic has air. */
    public boolean autoBuildIgnoreAir = true;
    /** A block the chests have no items for is skipped instead of pausing AutoBuild. */
    public boolean autoBuildSkipMissing = false;
    /** AutoBuild only builds within this many blocks of the player (0: the whole schematic). */
    public int autoBuildRadius = 0;
    /** AutoBuild builds only the block held in the hand when it starts ("build all of these"). */
    public boolean autoBuildOnlyHeld = false;
    /** Middle click on a ghost picks its item. */
    public boolean pickGhost = true;

    /** The info panel (bottom left by default) while a schematic is loaded. */
    public boolean progressHud = true;
    /** The small hint next to the crosshair: what a wrong block should be, what a ghost is. */
    public boolean crosshairHint = true;
    /** The tool panel (bottom right by default) while the selection tool is in hand: its mode and controls. */
    public boolean toolHud = true;
    /** Where the HUD pieces are and how big, set in the HUD editor. */
    public final HudLayout hud = new HudLayout();
    /**
     * The selection tool's item id: Alt+left-click a block for corner 1, Alt+right-click for corner 2, Ctrl+right-click
     * a chest to link it, Shift+right-click to clear the selection. Empty switches the tool off.
     */
    public String toolItem = "minecraft:stick";
    /**
     * The save selection and the placement boxes show always, instead of only while the selection tool is in either
     * hand. Ghosts show either way.
     */
    public boolean boxesAlways = false;
    /** Holding a block item gently marks the nearest ghosts that need it. */
    public boolean materialHelper = true;
    /** How many cells the material helper marks at most. */
    public int materialHelperCells = 48;

    /** A few particles when a ghost is filled correctly (and at celebrations). */
    public boolean particles = true;
    /** A low note for wrong placements (it follows the Blocks volume slider); the tutorial also cheers what goes right. */
    public boolean sounds = true;
    /** Toast and sparkles when a level is finished. */
    public boolean layerCelebration = true;
    /** After finishing the current level in layer mode, step to the next one. */
    public boolean autoAdvanceLayer = true;
    /** Fireworks and a summary when the whole schematic is done. */
    public boolean finishCelebration = true;

    /** Writes the shared progress file for BlockDesigner's BlockCompanion Plugin ({@code ~/.blockcompanion/progress}). */
    public boolean progressFile = true;
    /** The live link: BlockDesigner (BlockCompanion Plugin) can find this game, send projects and see progress. */
    public boolean link = true;
    /** Linked chests count in the resource list, the info panel and the BlockCompanion Plugin. */
    public boolean countChests = true;

    /** Ghost, mark, box and outline colours, set on the settings screen's Colours tab. */
    public final Palette colors = new Palette();

    /** The settings screen tab last open. */
    public String settingsTab = "";
    /** The guide has opened once (it opens by itself the first time you are in a world). */
    public boolean guideSeen = false;

    /** AutoBuild's options as set here (without the held block, which is looked up when it starts). */
    public io.blockcompanion.core.autobuild.AutoBuildOptions autoBuildDefaults() {
        return new io.blockcompanion.core.autobuild.AutoBuildOptions(autoBuildSpeed, autoBuildReplace, autoBuildOrder, autoBuildIgnoreAir,
                autoBuildSkipMissing, autoBuildRadius, "");
    }

    /** Sets the AutoBuild defaults from a build's options ("Save as defaults" on the AutoBuild options screen). */
    public void setAutoBuildDefaults(io.blockcompanion.core.autobuild.AutoBuildOptions o, boolean onlyHeld) {
        autoBuildSpeed = o.blocksPerSecond();
        autoBuildReplace = o.replace();
        autoBuildOrder = o.order();
        autoBuildIgnoreAir = o.ignoreAir();
        autoBuildSkipMissing = o.skipMissing();
        autoBuildRadius = o.radius();
        autoBuildOnlyHeld = onlyHeld;
    }

    public static ClientConfig load(Path file) {
        ClientConfig c = new ClientConfig();
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                BlockCompanionClient.LOG.warn("Could not read {}: {}", file, e.toString());
            }
        }
        int version = (int) parse(p.getProperty("config.version"), 1, 1, 1000);
        // Before version 3 Alt+scroll moved and plain scroll did the tool's mode; now Shift+scroll does the mode.
        c.moveModifier = version < 3 ? Modifier.SHIFT : Modifier.parse(p.getProperty("scroll.move.modifier"), Modifier.SHIFT);
        c.rotateModifier = Modifier.parse(p.getProperty("scroll.rotate.modifier"), Modifier.CTRL);
        c.clearModifier = Modifier.parse(p.getProperty("tool.clear.modifier"), Modifier.SHIFT);
        c.cornerModifier = Modifier.parse(p.getProperty("tool.corner.modifier"), Modifier.ALT);
        c.linkModifier = Modifier.parse(p.getProperty("tool.link.modifier"), Modifier.CTRL);
        c.toolMode = ToolMode.parse(p.getProperty("tool.mode"), ToolMode.MOVE);
        c.toolRequired = bool(p, "tool.requiredToMove", true);
        c.reach = parse(p.getProperty("placement.reach"), 96, 4, 512);
        // Version 1 files had faint ghosts (0.45); the near-solid look is the new default.
        c.ghostAlpha = version < 2 ? 0.85f : (float) parse(p.getProperty("ghost.alpha"), 0.85, 0.3, 1);
        c.ghostShimmer = bool(p, "ghost.shimmer", true);
        c.ghostDistance = (int) parse(p.getProperty("ghost.distance"), 64, 0, 512);
        c.interactRange = (int) parse(p.getProperty("interact.range"), 0, 0, 512);
        c.ghostBlockEntities = bool(p, "ghost.blockEntities", true);
        c.easyPlace = bool(p, "easyPlace.enabled", true);
        c.easyPlaceAutoPick = bool(p, "easyPlace.autoPick", true);
        c.easyPlaceAuto = bool(p, "easyPlace.auto", false);
        c.easyPlaceAutoRate = (int) parse(p.getProperty("easyPlace.autoRate"), io.blockcompanion.core.easyplace.AutoPlacePlanner.DEFAULT_RATE, 1,
                io.blockcompanion.core.easyplace.AutoPlacePlanner.MAX_RATE);
        c.restockCount = (int) parse(p.getProperty("chests.restockCount"), 64, 1, 576);
        c.autoBuildSpeed = (int) parse(p.getProperty("autoBuild.blocksPerSecond"), 5, 1, io.blockcompanion.core.autobuild.AutoBuildOptions.MAX_RATE);
        c.autoBuildReplace = io.blockcompanion.core.autobuild.AutoBuildOptions.Replace.parse(p.getProperty("autoBuild.replace"),
                io.blockcompanion.core.autobuild.AutoBuildOptions.Replace.KEEP);
        c.autoBuildOrder = io.blockcompanion.core.autobuild.AutoBuildOptions.Order.parse(p.getProperty("autoBuild.order"),
                io.blockcompanion.core.autobuild.AutoBuildOptions.Order.BOTTOM_UP);
        c.autoBuildIgnoreAir = bool(p, "autoBuild.ignoreAir", true);
        c.autoBuildSkipMissing = bool(p, "autoBuild.skipMissing", false);
        c.autoBuildRadius = (int) parse(p.getProperty("autoBuild.radius"), 0, 0, io.blockcompanion.core.autobuild.AutoBuildOptions.MAX_RADIUS);
        c.autoBuildOnlyHeld = "held".equalsIgnoreCase(p.getProperty("autoBuild.only", "all").trim());
        c.pickGhost = bool(p, "pickBlock.ghosts", true);
        c.progressHud = bool(p, "hud.progress", true);
        c.crosshairHint = bool(p, "hud.hint", true);
        c.toolHud = bool(p, "hud.toolPanel", true);
        c.hud.read(p);
        c.toolItem = p.getProperty("tool.item", "minecraft:stick").trim();
        c.boxesAlways = "always".equalsIgnoreCase(p.getProperty("boxes.show", "tool").trim());
        c.materialHelper = bool(p, "materialHelper.enabled", true);
        c.materialHelperCells = (int) parse(p.getProperty("materialHelper.cells"), 48, 1, 512);
        c.particles = bool(p, "effects.particles", true);
        c.sounds = bool(p, "effects.sounds", true);
        c.layerCelebration = bool(p, "effects.layerCelebration", true);
        c.autoAdvanceLayer = bool(p, "layers.autoAdvance", true);
        c.finishCelebration = bool(p, "effects.finishCelebration", true);
        c.progressFile = bool(p, "progress.file", true);
        c.link = bool(p, "link.enabled", true);
        c.countChests = bool(p, "chests.count", true);
        c.colors.read(p);
        c.settingsTab = p.getProperty("settings.tab", "").trim();
        c.guideSeen = bool(p, "guide.seen", false);
        c.save(file);
        return c;
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        if (v == null) return fallback;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0")) return false;
        return fallback;
    }

    private static double parse(String s, double fallback, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(s.trim())));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public void save(Path file) {
        Properties p = new Properties();
        p.setProperty("config.version", Integer.toString(VERSION));
        p.setProperty("scroll.move.modifier", moveModifier.name());
        p.setProperty("scroll.rotate.modifier", rotateModifier.name());
        p.setProperty("tool.clear.modifier", clearModifier.name());
        p.setProperty("tool.corner.modifier", cornerModifier.name());
        p.setProperty("tool.link.modifier", linkModifier.name());
        p.setProperty("tool.mode", toolMode.name());
        p.setProperty("tool.requiredToMove", Boolean.toString(toolRequired));
        p.setProperty("placement.reach", Double.toString(reach));
        p.setProperty("ghost.alpha", Float.toString(ghostAlpha));
        p.setProperty("ghost.shimmer", Boolean.toString(ghostShimmer));
        p.setProperty("ghost.distance", Integer.toString(ghostDistance));
        p.setProperty("interact.range", Integer.toString(interactRange));
        p.setProperty("ghost.blockEntities", Boolean.toString(ghostBlockEntities));
        p.setProperty("easyPlace.enabled", Boolean.toString(easyPlace));
        p.setProperty("easyPlace.autoPick", Boolean.toString(easyPlaceAutoPick));
        p.setProperty("easyPlace.auto", Boolean.toString(easyPlaceAuto));
        p.setProperty("easyPlace.autoRate", Integer.toString(easyPlaceAutoRate));
        p.setProperty("chests.restockCount", Integer.toString(restockCount));
        p.setProperty("autoBuild.blocksPerSecond", Integer.toString(autoBuildSpeed));
        p.setProperty("autoBuild.replace", autoBuildReplace.key());
        p.setProperty("autoBuild.order", autoBuildOrder.name());
        p.setProperty("autoBuild.ignoreAir", Boolean.toString(autoBuildIgnoreAir));
        p.setProperty("autoBuild.skipMissing", Boolean.toString(autoBuildSkipMissing));
        p.setProperty("autoBuild.radius", Integer.toString(autoBuildRadius));
        p.setProperty("autoBuild.only", autoBuildOnlyHeld ? "held" : "all");
        p.setProperty("pickBlock.ghosts", Boolean.toString(pickGhost));
        p.setProperty("hud.progress", Boolean.toString(progressHud));
        p.setProperty("hud.hint", Boolean.toString(crosshairHint));
        p.setProperty("hud.toolPanel", Boolean.toString(toolHud));
        hud.write(p);
        p.setProperty("tool.item", toolItem);
        p.setProperty("boxes.show", boxesAlways ? "always" : "tool");
        p.setProperty("materialHelper.enabled", Boolean.toString(materialHelper));
        p.setProperty("materialHelper.cells", Integer.toString(materialHelperCells));
        p.setProperty("effects.particles", Boolean.toString(particles));
        p.setProperty("effects.sounds", Boolean.toString(sounds));
        p.setProperty("effects.layerCelebration", Boolean.toString(layerCelebration));
        p.setProperty("layers.autoAdvance", Boolean.toString(autoAdvanceLayer));
        p.setProperty("effects.finishCelebration", Boolean.toString(finishCelebration));
        p.setProperty("progress.file", Boolean.toString(progressFile));
        p.setProperty("link.enabled", Boolean.toString(link));
        p.setProperty("chests.count", Boolean.toString(countChests));
        colors.write(p);
        p.setProperty("settings.tab", settingsTab);
        p.setProperty("guide.seen", Boolean.toString(guideSeen));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "BlockCompanion. Also on the settings screen in the game. Modifiers: ALT, CTRL, SHIFT or NONE (that"
                        + " action off); scroll.move.modifier + scroll.rotate.modifier together switch tool.mode. ghost.alpha 0.3 to 1. tool.item empty switches the selection tool off. boxes.show tool or always. tool.mode: MOVE or MIRROR. autoBuild.replace: keep, solid, all or clear. autoBuild.order: BOTTOM_UP, TOP_DOWN, NEAREST or BY_BLOCK. autoBuild.radius 0 builds the whole schematic. autoBuild.only: all or held. color.* are #RRGGBB. Keys are in Options > Controls.");
            }
        } catch (IOException e) {
            BlockCompanionClient.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }
}
