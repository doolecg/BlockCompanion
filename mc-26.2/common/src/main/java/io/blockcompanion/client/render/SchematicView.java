package io.blockcompanion.client.render;

import io.blockcompanion.client.StateMapper;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * The placed schematic seen as a world, in world coordinates, for the block model renderer: faces between two ghost
 * blocks get culled and connected textures line up. Hidden layers read as air. Tint and light come from the real
 * world at the same spot.
 */
final class SchematicView implements BlockAndTintGetter {
    private final ClientLevel level;
    private final Placement placement;
    private final Layers layers;
    private final Box box;

    SchematicView(ClientLevel level, Placement placement, Layers layers) {
        this.level = level;
        this.placement = placement;
        this.layers = layers;
        this.box = placement.worldBox();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (!box.contains(x, y, z) || !layers.isVisible(y - box.minY())) return Blocks.AIR.defaultBlockState();
        io.blockcompanion.core.model.BlockState s = placement.stateAt(x, y, z);
        if (s.isAir()) return Blocks.AIR.defaultBlockState();
        BlockState mc = StateMapper.toMc(s);
        return mc == null ? Blocks.AIR.defaultBlockState() : mc;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return level.cardinalLighting();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return level.getBlockTint(pos, resolver);
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }
}
