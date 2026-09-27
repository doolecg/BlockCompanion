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
    /** Marks a corner of the region to save (first, then the opposite one). */
    public static final KeyMapping SELECT_CORNER = key("select_corner", GLFW.GLFW_KEY_K);
    /** Opens the save screen: saves the marked region (or the loaded schematic's box) as .schem. */
    public static final KeyMapping SAVE = key("save", GLFW.GLFW_KEY_O);

    /** Switches easy place (right-click on a ghost places exactly its block) on and off. */
    public static final KeyMapping EASY_PLACE = key("easy_place", GLFW.GLFW_KEY_H);
    /** Locks the looked-at (or selected) placement in place, or unlocks it. */
    public static final KeyMapping LOCK = key("lock", GLFW.GLFW_KEY_Y);
    /** Selects the next loaded placement (the one the keys act on when you don't look at a box). */
    public static final KeyMapping NEXT_PLACEMENT = key("next_placement", InputConstants.UNKNOWN.getValue());
    /** Asks BlockDesigner for the project it has open. */
    public static final KeyMapping GRAB = key("grab", InputConstants.UNKNOWN.getValue());
    /** Opens the settings screen. */
    public static final KeyMapping SETTINGS = key("settings", InputConstants.UNKNOWN.getValue());

    public static final List<KeyMapping> ALL = List.of(LIBRARY, RESOURCES, MIRROR, LAYER_UP, LAYER_DOWN, LAYER_MODE, TOGGLE_VISIBLE,
            SELECT_CORNER, SAVE, EASY_PLACE, LOCK, NEXT_PLACEMENT, GRAB, SETTINGS);

    private Keys() {
    }

    private static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping("key.blockcompanion." + name, InputConstants.Type.KEYSYM, glfwKey, CATEGORY);
    }
}
