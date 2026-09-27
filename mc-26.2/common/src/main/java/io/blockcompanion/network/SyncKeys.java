package io.blockcompanion.network;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.Keys;
import net.minecraft.client.KeyMapping;

/**
 * The shared-space key (client only), registered by the platforms' sync client entry points next to {@link Keys#ALL}.
 * Created after {@link Keys#create}, since a 26.x key mapping needs its category object.
 */
public final class SyncKeys {
    public static KeyMapping SHARED;

    private SyncKeys() {
    }

    public static KeyMapping create() {
        if (SHARED == null) SHARED = new KeyMapping("key.blockcompanion.shared", InputConstants.Type.KEYSYM, InputConstants.KEY_J, Keys.CATEGORY);
        return SHARED;
    }
}
