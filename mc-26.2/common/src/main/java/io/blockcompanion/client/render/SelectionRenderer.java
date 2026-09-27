package io.blockcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.model.Box;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/** Draws the save selection: an outline (cyan by default) around the marked box (one block while only the first corner is set). */
public final class SelectionRenderer {
    private static int color() {
        return BlockCompanionClient.config().colors.argb(Palette.Entry.SELECTION, 0xFF);
    }


    public void clear() {
        // Nothing kept between frames: the outline is submitted fresh each frame.
    }

    public void submit(Box box, SubmitNodeCollector collector, PoseStack poseStack, Vec3 cam) {
        float e = 0.01f;
        float sx = box.sizeX() + e, sy = box.sizeY() + e, sz = box.sizeZ() + e;
        float[][] c = {{-e, -e, -e}, {sx, -e, -e}, {sx, -e, sz}, {-e, -e, sz}, {-e, sy, -e}, {sx, sy, -e}, {sx, sy, sz}, {-e, sy, sz}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        poseStack.pushPose();
        poseStack.translate(box.minX() - cam.x, box.minY() - cam.y, box.minZ() - cam.z);
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, out) -> {
            for (int[] edge : edges) {
                float[] a = c[edge[0]], b = c[edge[1]];
                float nx = b[0] - a[0], ny = b[1] - a[1], nz = b[2] - a[2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= len;
                ny /= len;
                nz /= len;
                out.addVertex(pose, a[0], a[1], a[2]).setColor(color()).setNormal(pose, nx, ny, nz).setLineWidth(2f);
                out.addVertex(pose, b[0], b[1], b[2]).setColor(color()).setNormal(pose, nx, ny, nz).setLineWidth(2f);
            }
        });
        poseStack.popPose();
    }
}
