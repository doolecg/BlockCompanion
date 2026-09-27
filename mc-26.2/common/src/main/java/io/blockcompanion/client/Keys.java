package io.blockcompanion.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Function;

/**
 * The key mappings; each platform registers them so they show up (and can be rebound) in Options > Controls. The
 * category is made by the platform: Fabric registers it through {@code KeyMapping.Category.register}, NeoForge through
 * its key mapping event.
 */
public final class Keys {
    /** Shows as "key.category.blockcompanion.main" in the Controls screen. */
    public static final Identifier CATEGORY_ID = Identifier.fromNamespaceAndPath("blockcompanion", "main");

    public static KeyMapping.Category CATEGORY;
    public static KeyMapping LIBRARY, RESOURCES, MIRROR, LAYER_UP, LAYER_DOWN, LAYER_MODE, TOGGLE_VISIBLE, SELECT_CORNER, SAVE, EASY_PLACE, LOCK,
            NEXT_PLACEMENT, GRAB, SETTINGS;
    public static List<KeyMapping> ALL = List.of();

    private Keys() {
    }

    /** Creates the category (with the platform's factory) and every key. Call once, before registering them. */
    public static List<KeyMapping> create(Function<Identifier, KeyMapping.Category> category) {
        if (!ALL.isEmpty()) return ALL;
        CATEGORY = category.apply(CATEGORY_ID);
        LIBRARY = key("library", InputConstants.KEY_B);
        RESOURCES = key("resources", InputConstants.KEY_N);
        MIRROR = key("mirror", InputConstants.KEY_M);
        LAYER_UP = key("layer_up", InputConstants.KEY_PAGEUP);
        LAYER_DOWN = key("layer_down", InputConstants.KEY_PAGEDOWN);
        LAYER_MODE = key("layer_mode", InputConstants.KEY_INSERT);
        TOGGLE_VISIBLE = key("toggle_visible", InputConstants.UNKNOWN.getValue());
        // Marks a corner of the region to save; the save screen saves it (or the loaded schematic's box) as .schem.
        SELECT_CORNER = key("select_corner", InputConstants.KEY_K);
        SAVE = key("save", InputConstants.KEY_O);
        // Switches easy place (right-click on a ghost places exactly its block) on and off.
        EASY_PLACE = key("easy_place", InputConstants.KEY_H);
        // Locks the looked-at (or selected) placement in place, or unlocks it.
        LOCK = key("lock", InputConstants.KEY_Y);
        // Selects the next loaded placement; asks BlockDesigner for its project; opens the settings.
        NEXT_PLACEMENT = key("next_placement", InputConstants.UNKNOWN.getValue());
        GRAB = key("grab", InputConstants.UNKNOWN.getValue());
        SETTINGS = key("settings", InputConstants.UNKNOWN.getValue());
        ALL = List.of(LIBRARY, RESOURCES, MIRROR, LAYER_UP, LAYER_DOWN, LAYER_MODE, TOGGLE_VISIBLE, SELECT_CORNER, SAVE, EASY_PLACE, LOCK,
                NEXT_PLACEMENT, GRAB, SETTINGS);
        return ALL;
    }

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.blockcompanion." + name, InputConstants.Type.KEYSYM, key, CATEGORY);
    }
}
