package io.blockcompanion.mixin;

import io.blockcompanion.client.BlockCompanionClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change and chunk load ends up marking a world section for re-meshing; the ghost mesh for the same
 * section is rebuilt then, and only then. Single block changes (any player's, and the client's own prediction) also
 * update the build progress at once. When everything is re-meshed (new resource packs, F3+T) the ghosts are rebuilt
 * with the new models too.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void blockcompanion$onSectionDirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        BlockCompanionClient.onWorldSectionDirty(x, y, z);
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void blockcompanion$onAllChanged(CallbackInfo ci) {
        BlockCompanionClient.onAllChanged();
    }

    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void blockcompanion$onBlockChanged(BlockGetter level, BlockPos pos, BlockState before, BlockState after, int flags, CallbackInfo ci) {
        BlockCompanionClient.onBlockChanged(pos, after);
    }
}
