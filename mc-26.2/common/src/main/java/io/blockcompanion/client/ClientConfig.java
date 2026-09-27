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
    private static final int VERSION = 2;

    /** A modifier key held while scrolling. */
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

        static Modifier parse(String s, Modifier fallback) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                return fallback;
            }
        }
    }

    /** Held while scrolling, moves the placement along the looked-at face's axis. */
    public Modifier moveModifier = Modifier.ALT;
    /** Held while scrolling, turns the placement 90 degrees around Y. */
    public Modifier rotateModifier = Modifier.CTRL;
    /** Held while scrolling with the selection tool in hand, steps through the tool's scroll modes. */
    public Modifier modeModifier = Modifier.SHIFT;
    /** What plain scrolling does with the selection tool in hand while looking at a box. */
    public ToolMode toolMode = ToolMode.MOVE;
    /**
     * Moving, turning and mirroring a placement in the world (scrolling, the mirror key) and undo/redo only work with the
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

    /** Right-click on a ghost with its item places exactly that block. Can be toggled with its key. */
    public boolean easyPlace = true;
    /** Easy place brings the ghost's block from the inventory into the hand when it isn't held. */
    public boolean easyPlaceAutoPick = true;
    /** How many items one fetch from the linked chests asks for. */
    public int restockCount = 64;
    /** Middle click on a ghost picks its item. */
    public boolean pickGhost = true;

    /** The info panel (bottom left by default) while a schematic is loaded. */
    public boolean progressHud = true;
    /** The small hint next to the crosshair: what a wrong block should be, what a ghost is. */
    public boolean crosshairHint = true;
    /** Where the HUD pieces are and how big, set in the HUD editor. */
    public final HudLayout hud = new HudLayout();
    /**
     * The selection tool's item id: left-click a block for corner 1, right-click for corner 2, sneak + right-click a
     * chest to link it. Empty switches the tool off.
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
    /** Soft sounds for correct and wrong placements and celebrations (they follow the Blocks volume slider). */
    public boolean sounds = true;
    /** The chime rises with quick correct placements in a row. */
    public boolean combo = true;
    /** Toast and sparkles when a level is finished. */
    public boolean layerCelebration = true;
    /** After finishing the current level in layer mode, step to the next one. */
    public boolean autoAdvanceLayer = true;
    /** Fireworks and a summary when the whole schematic is done. */
    public boolean finishCelebration = true;

    /** Writes the shared progress file for BlockDesigner's Resource Tracker ({@code ~/.blockcompanion/progress}). */
    public boolean progressFile = true;
    /** The live link: BlockDesigner (Resource Tracker) can find this game, send projects and see progress. */
    public boolean link = true;
    /** Linked chests count in the resource list, the info panel and Resource Tracker. */
    public boolean countChests = true;

    /** Ghost, mark, box and outline colours, set on the settings screen's Colours tab. */
    public final Palette colors = new Palette();

    /** Look for a newer release on GitHub at start and every few hours. */
    public boolean updateCheck = true;
    /** Download a found update straight away; it installs when the game quits. */
    public boolean updateAutoDownload = false;
    /** The settings screen tab last open. */
    public String settingsTab = "";

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
        c.moveModifier = Modifier.parse(p.getProperty("scroll.move.modifier"), Modifier.ALT);
        c.rotateModifier = Modifier.parse(p.getProperty("scroll.rotate.modifier"), Modifier.CTRL);
        c.modeModifier = Modifier.parse(p.getProperty("scroll.mode.modifier"), Modifier.SHIFT);
        c.toolMode = ToolMode.parse(p.getProperty("tool.mode"), ToolMode.MOVE);
        c.toolRequired = bool(p, "tool.requiredToMove", true);
        c.reach = parse(p.getProperty("placement.reach"), 96, 4, 512);
        // Version 1 files had faint ghosts (0.45); the near-solid look is the new default.
        c.ghostAlpha = version < 2 ? 0.85f : (float) parse(p.getProperty("ghost.alpha"), 0.85, 0.3, 1);
        c.ghostShimmer = bool(p, "ghost.shimmer", true);
        c.ghostBlockEntities = bool(p, "ghost.blockEntities", true);
        c.easyPlace = bool(p, "easyPlace.enabled", true);
        c.easyPlaceAutoPick = bool(p, "easyPlace.autoPick", true);
        c.restockCount = (int) parse(p.getProperty("chests.restockCount"), 64, 1, 576);
        c.pickGhost = bool(p, "pickBlock.ghosts", true);
        c.progressHud = bool(p, "hud.progress", true);
        c.crosshairHint = bool(p, "hud.hint", true);
        c.hud.read(p);
        c.toolItem = p.getProperty("tool.item", "minecraft:stick").trim();
        c.boxesAlways = "always".equalsIgnoreCase(p.getProperty("boxes.show", "tool").trim());
        c.materialHelper = bool(p, "materialHelper.enabled", true);
        c.materialHelperCells = (int) parse(p.getProperty("materialHelper.cells"), 48, 1, 512);
        c.particles = bool(p, "effects.particles", true);
        c.sounds = bool(p, "effects.sounds", true);
        c.combo = bool(p, "effects.combo", true);
        c.layerCelebration = bool(p, "effects.layerCelebration", true);
        c.autoAdvanceLayer = bool(p, "layers.autoAdvance", true);
        c.finishCelebration = bool(p, "effects.finishCelebration", true);
        c.progressFile = bool(p, "progress.file", true);
        c.link = bool(p, "link.enabled", true);
        c.countChests = bool(p, "chests.count", true);
        c.colors.read(p);
        c.updateCheck = bool(p, "updates.check", true);
        c.updateAutoDownload = bool(p, "updates.autoDownload", false);
        c.settingsTab = p.getProperty("settings.tab", "").trim();
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
        p.setProperty("scroll.mode.modifier", modeModifier.name());
        p.setProperty("tool.mode", toolMode.name());
        p.setProperty("tool.requiredToMove", Boolean.toString(toolRequired));
        p.setProperty("placement.reach", Double.toString(reach));
        p.setProperty("ghost.alpha", Float.toString(ghostAlpha));
        p.setProperty("ghost.shimmer", Boolean.toString(ghostShimmer));
        p.setProperty("ghost.blockEntities", Boolean.toString(ghostBlockEntities));
        p.setProperty("easyPlace.enabled", Boolean.toString(easyPlace));
        p.setProperty("easyPlace.autoPick", Boolean.toString(easyPlaceAutoPick));
        p.setProperty("chests.restockCount", Integer.toString(restockCount));
        p.setProperty("pickBlock.ghosts", Boolean.toString(pickGhost));
        p.setProperty("hud.progress", Boolean.toString(progressHud));
        p.setProperty("hud.hint", Boolean.toString(crosshairHint));
        hud.write(p);
        p.setProperty("tool.item", toolItem);
        p.setProperty("boxes.show", boxesAlways ? "always" : "tool");
        p.setProperty("materialHelper.enabled", Boolean.toString(materialHelper));
        p.setProperty("materialHelper.cells", Integer.toString(materialHelperCells));
        p.setProperty("effects.particles", Boolean.toString(particles));
        p.setProperty("effects.sounds", Boolean.toString(sounds));
        p.setProperty("effects.combo", Boolean.toString(combo));
        p.setProperty("effects.layerCelebration", Boolean.toString(layerCelebration));
        p.setProperty("layers.autoAdvance", Boolean.toString(autoAdvanceLayer));
        p.setProperty("effects.finishCelebration", Boolean.toString(finishCelebration));
        p.setProperty("progress.file", Boolean.toString(progressFile));
        p.setProperty("link.enabled", Boolean.toString(link));
        p.setProperty("chests.count", Boolean.toString(countChests));
        colors.write(p);
        p.setProperty("updates.check", Boolean.toString(updateCheck));
        p.setProperty("updates.autoDownload", Boolean.toString(updateAutoDownload));
        p.setProperty("settings.tab", settingsTab);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "BlockCompanion. Also on the settings screen in the game. Modifiers: ALT, CTRL, SHIFT or NONE (scroll"
                        + " action off). ghost.alpha 0.3 to 1. tool.item empty switches the selection tool off. boxes.show tool or always. tool.mode: MOVE, ROTATE, MIRROR, LAYER or VISIBILITY. color.* are #RRGGBB. Keys are in Options > Controls.");
            }
        } catch (IOException e) {
            BlockCompanionClient.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }
}
