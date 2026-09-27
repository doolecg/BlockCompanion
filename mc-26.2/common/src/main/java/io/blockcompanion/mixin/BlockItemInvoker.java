package io.blockcompanion.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Easy place asks the item which state a planned click would place (wall or standing torch, the block's own rule). */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState blockcompanion$placementState(BlockPlaceContext context);
}
