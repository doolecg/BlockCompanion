package io.blockcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.BoxLook;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.model.Box;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the save selection (cyan by default) around the marked box, one block while only the first corner is set,
 * in the {@link BoxRenderer} look.
 */
public final class SelectionRenderer {
    public void clear() {
        // Nothing kept between frames: the box is submitted fresh each frame.
    }

    public void submit(Box box, SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam, Vec3 look) {
        int rgb = BlockCompanionClient.config().colors.get(Palette.Entry.SELECTION);
        int face = BoxRenderer.lookedFace(box, cam, look, BlockCompanionClient.config().reach);
        BoxRenderer.submit(box, rgb, BoxLook.EDGE_ALPHA, face, false, collector, poseStack, cam);
    }
}
