package io.blockcompanion.mixin;

import io.blockcompanion.client.BlockCompanionClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shift / Ctrl + scroll on a box moves, mirrors or turns it (Ctrl+Shift switches the tool mode): none change the hotbar slot. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (BlockCompanionClient.onScroll(yOffset)) ci.cancel();
    }
}
