package io.blockcompanion.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** The key mappings; each platform registers them so they show up (and can be rebound) in Options > Controls. */
public final class Keys {
    public static final String CATEGORY = "key.categories.blockcompanion";

    public static final KeyMapping LIBRARY = key("library", GLFW.GLFW_KEY_B);
    public static final KeyMapping RESOURCES = key("resources", GLFW.GLFW_KEY_N);
    public static final KeyMapping MIRROR = key("mirror", GLFW.GLFW_KEY_M);
    public static final KeyMapping LAYER_UP = key("layer_up", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping LAYER_DOWN = key("layer_down", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping LAYER_MODE = key("layer_mode", GLFW.GLFW_KEY_INSERT);
    public static final KeyMapping TOGGLE_VISIBLE = key("toggle_visible", InputConstants.UNKNOWN.getValue());
    /** Steps the looked-at schematic's view: everything, layers up to here, this layer only, only this one, hidden. */
    public static final KeyMapping VIEW = key("view", GLFW.GLFW_KEY_V);
    /** Marks a corner of the region to save (first, then the opposite one). */
    public static final KeyMapping SELECT_CORNER = key("select_corner", GLFW.GLFW_KEY_K);
    /** Opens the save screen: saves the marked region (or the loaded schematic's box) as .schem. */
    public static final KeyMapping SAVE = key("save", GLFW.GLFW_KEY_O);

    /** Switches easy place (right-click on a ghost places exactly its block) on and off. */
    public static final KeyMapping EASY_PLACE = key("easy_place", GLFW.GLFW_KEY_H);
    /** Switches easy place's auto mode (missing blocks in reach place themselves) on and off. No key by default. */
    public static final KeyMapping EASY_PLACE_AUTO = key("easy_place_auto", InputConstants.UNKNOWN.getValue());
    /**
     * Undo and redo of placement changes: pressed with Ctrl, across every placement like any program (Ctrl+Shift+undo redoes
     * too). Made before the lock key, which shares Y by default, so Y without Ctrl still reaches the lock key here (1.21.1
     * clicks only one mapping per key; the tick passes the redo key's clicks on to the lock key when they share it).
     */
    public static final KeyMapping UNDO = key("undo", GLFW.GLFW_KEY_Z);
    public static final KeyMapping REDO = key("redo", GLFW.GLFW_KEY_Y);
    /** Locks the looked-at (or selected) placement in place, or unlocks it. */
    public static final KeyMapping LOCK = key("lock", GLFW.GLFW_KEY_Y);
    /** Selects the next loaded placement (the one the keys act on when you don't look at a box). */
    public static final KeyMapping NEXT_PLACEMENT = key("next_placement", InputConstants.UNKNOWN.getValue());
    /** Asks BlockDesigner for the project it has open. */
    public static final KeyMapping GRAB = key("grab", InputConstants.UNKNOWN.getValue());
    /** Opens the settings screen. */
    public static final KeyMapping SETTINGS = key("settings", InputConstants.UNKNOWN.getValue());
    /** Rendering and HUD toggles: they flip the same options as the settings screen. No keys by default. */
    public static final KeyMapping TOGGLE_HUD = key("toggle_hud", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping TOGGLE_SHIMMER = key("toggle_shimmer", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping TOGGLE_GHOST_ENTITIES = key("toggle_ghost_entities", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping TOGGLE_BOXES = key("toggle_boxes", InputConstants.UNKNOWN.getValue());
    public static final KeyMapping TOGGLE_MATERIAL_HELPER = key("toggle_material_helper", InputConstants.UNKNOWN.getValue());

    public static final List<KeyMapping> ALL = List.of(LIBRARY, RESOURCES, MIRROR, LAYER_UP, LAYER_DOWN, LAYER_MODE, TOGGLE_VISIBLE, VIEW,
            SELECT_CORNER, SAVE, EASY_PLACE, EASY_PLACE_AUTO, LOCK, NEXT_PLACEMENT, GRAB, SETTINGS, UNDO, REDO,
            TOGGLE_HUD, TOGGLE_SHIMMER, TOGGLE_GHOST_ENTITIES, TOGGLE_BOXES, TOGGLE_MATERIAL_HELPER);

    private Keys() {
    }

    private static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping("key.blockcompanion." + name, InputConstants.Type.KEYSYM, glfwKey, CATEGORY);
    }
}
