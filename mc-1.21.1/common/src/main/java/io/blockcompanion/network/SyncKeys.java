package io.blockcompanion.network;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.Keys;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** The shared-space key (client only), registered by the platforms' sync client entry points next to {@link Keys#ALL}. */
public final class SyncKeys {
    public static final KeyMapping SHARED = new KeyMapping("key.blockcompanion.shared", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, Keys.CATEGORY);

    private SyncKeys() {
    }
}
