package io.blockcompanion.mixin;

import io.blockcompanion.client.BlockCompanionClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicks, before vanilla handles them: right-click for the selection tool and easy place, middle click for picking a
 * ghost's block, left click for the selection tool's first corner (the tool never mines while aimed at a block).
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow
    private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onUse(CallbackInfo ci) {
        if (BlockCompanionClient.onUseItem()) {
            // The same pause between repeated clicks as vanilla's.
            rightClickDelay = 4;
            ci.cancel();
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onAttack(CallbackInfoReturnable<Boolean> cir) {
        if (BlockCompanionClient.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (leftClick && BlockCompanionClient.blocksMining()) ci.cancel();
    }

    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void blockcompanion$onPick(CallbackInfo ci) {
        if (BlockCompanionClient.onPickBlock()) ci.cancel();
    }
}
