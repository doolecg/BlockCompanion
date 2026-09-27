package io.blockcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.BoxLook;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.RayBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a box in the world (the save selection and the placement boxes) the {@link BoxLook} way: faint tinted faces
 * on all six sides (seen from inside too, never writing depth), the looked-at face a little brighter, a crisp outline
 * and lighter corner brackets. Boxes show while the selection tool is in either hand (or always, per the settings) and
 * fade in and out quickly.
 */
public final class BoxRenderer {
    private static double fade;
    private static long lastFrame;

    private BoxRenderer() {
    }

    /** Once per frame: steps the fade toward shown or hidden. Returns the fade (0 = hidden). */
    public static double frame() {
        long now = System.currentTimeMillis();
        long dt = lastFrame == 0 ? 0 : Math.min(250, now - lastFrame);
        lastFrame = now;
        fade = BoxLook.stepFade(fade, shouldShow(), dt);
        return fade;
    }

    public static double fade() {
        return fade;
    }

    private static boolean shouldShow() {
        if (BlockCompanionClient.config().boxesAlways) return true;
        String id = BlockCompanionClient.config().toolItem;
        // With the tool switched off there is nothing to hold: keep the boxes.
        if (id == null || id.isBlank()) return true;
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && (is(p.getMainHandItem(), id) || is(p.getOffhandItem(), id));
    }

    private static boolean is(ItemStack stack, String id) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id);
    }

    /** The face of {@code box} the view ray points at (see {@link BoxLook#face}), or -1. */
    public static int lookedFace(Box box, Vec3 cam, Vec3 look, double reach) {
        RayBox.Hit h = RayBox.intersect(cam.x, cam.y, cam.z, look.x, look.y, look.z, box, reach);
        return h == null ? -1 : BoxLook.face(h.axis(), h.sign());
    }

    /**
     * Submits one box. {@code edgeAlpha} is the outline's opacity (0 to 1); {@code face} the looked-at face or -1;
     * {@code thick} draws the outline a little wider.
     */
    public static void submit(Box box, int rgb, double edgeAlpha, int face, boolean thick, SubmitNodeCollector collector, PoseStack poseStack,
                              Vec3 cam) {
        double f = fade;
        if (f <= 0) return;
        float e = BoxLook.INFLATE;
        float x0 = -e, y0 = -e, z0 = -e, x1 = box.sizeX() + e, y1 = box.sizeY() + e, z1 = box.sizeZ() + e;
        long now = System.currentTimeMillis();
        int faceColor = BoxLook.faceArgb(rgb, false, now, f), lookedColor = BoxLook.faceArgb(rgb, true, now, f);
        int edge = BoxLook.edgeArgb(rgb, edgeAlpha, f), accent = BoxLook.accentArgb(rgb, edgeAlpha, f);
        float[] quads = BoxLook.faceQuads(x0, y0, z0, x1, y1, z1);
        float[] edges = BoxLook.edges(x0, y0, z0, x1, y1, z1);
        float a = e * 1.5f;
        float[] brackets = BoxLook.brackets(-a, -a, -a, box.sizeX() + a, box.sizeY() + a, box.sizeZ() + a);
        float width = thick ? 3f : 2f;

        poseStack.pushPose();
        poseStack.translate(box.minX() - cam.x, box.minY() - cam.y, box.minZ() - cam.z);
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, out) -> {
            for (int q = 0; q < 6; q++) {
                int col = q == face ? lookedColor : faceColor;
                for (int i = q * 12, end = i + 12; i < end; i += 3) out.addVertex(pose, quads[i], quads[i + 1], quads[i + 2]).setColor(col);
            }
        });
        collector.submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(), (pose, out) -> {
            segments(out, pose, edges, edge, width);
            segments(out, pose, brackets, accent, width + 1.5f);
        });
        poseStack.popPose();
    }

    private static void segments(VertexConsumer out, PoseStack.Pose pose, float[] s, int color, float width) {
        for (int i = 0; i < s.length; i += 6) {
            float nx = s[i + 3] - s[i], ny = s[i + 4] - s[i + 1], nz = s[i + 5] - s[i + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len == 0) continue;
            nx /= len;
            ny /= len;
            nz /= len;
            out.addVertex(pose, s[i], s[i + 1], s[i + 2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
            out.addVertex(pose, s[i + 3], s[i + 4], s[i + 5]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        }
    }
}
