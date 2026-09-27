package io.blockcompanion.mixin;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.Keys;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
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
    /** {@code action}: 0 released, 1 pressed, 2 held down (repeat). */
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action == 0 || Keys.UNDO == null) return;
        if (BlockCompanionClient.onUndoKey(Keys.UNDO.matches(event), Keys.REDO.matches(event), event.hasControlDownWithQuirk(), event.hasShiftDown())) {
            ci.cancel();
        }
    }
}
