package io.blockcompanion.mixin;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.Keys;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ctrl + the undo key (Z) and Ctrl + the redo key (Y) or Ctrl+Shift+Z undo and redo placement changes, like any
 * program. Taken before the game sees the key, so Ctrl+Y doesn't also press the lock key that shares Y.
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (action == GLFW.GLFW_RELEASE || window != Minecraft.getInstance().getWindow().getWindow()) return;
        // Enter skips a step of the tutorial.
        if (action == GLFW.GLFW_PRESS && io.blockcompanion.client.Tutorial.onKey(key)) {
            ci.cancel();
            return;
        }
        // Cmd on macOS, like the game's own Ctrl shortcuts.
        boolean control = (modifiers & (Minecraft.ON_OSX ? GLFW.GLFW_MOD_SUPER : GLFW.GLFW_MOD_CONTROL)) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (BlockCompanionClient.onUndoKey(Keys.UNDO.matches(key, scancode), Keys.REDO.matches(key, scancode), control, shift)) ci.cancel();
    }
}
