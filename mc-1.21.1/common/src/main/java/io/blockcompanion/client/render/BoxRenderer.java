package io.blockcompanion.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.BoxLook;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.placement.RayBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws a box in the world (the save selection and the placement boxes) the {@link BoxLook} way: faint tinted faces
 * on all six sides (seen from inside too, never writing depth), the looked-at face a little brighter, a crisp outline
 * and lighter corner brackets. Boxes show while the selection tool is in either hand (or always, per the settings) and
 * fade in and out quickly.
 */
public final class BoxRenderer {
    private static double fade;
    private static long lastFrame;
    private static ByteBufferBuilder bytes;
    private static VertexBuffer faces, lines;

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
    public static int lookedFace(Box box, Vec3 cam, double lx, double ly, double lz, double reach) {
        RayBox.Hit h = RayBox.intersect(cam.x, cam.y, cam.z, lx, ly, lz, box, reach);
        return h == null ? -1 : BoxLook.face(h.axis(), h.sign());
    }

    /**
     * Draws one box. {@code modelView} is the camera rotation (no translation). {@code edgeAlpha} is the outline's
     * opacity (0 to 1); {@code face} the looked-at face or -1; {@code thick} draws the outline a little wider.
     */
    public static void render(Box box, int rgb, double edgeAlpha, int face, boolean thick, Matrix4f modelView, Matrix4f projection, Vec3 cam) {
        double f = fade;
        if (f <= 0) return;
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) return;
        float e = BoxLook.INFLATE;
        float x0 = -e, y0 = -e, z0 = -e, x1 = box.sizeX() + e, y1 = box.sizeY() + e, z1 = box.sizeZ() + e;
        long now = System.currentTimeMillis();
        int faceColor = BoxLook.faceArgb(rgb, false, now, f), lookedColor = BoxLook.faceArgb(rgb, true, now, f);
        int edge = BoxLook.edgeArgb(rgb, edgeAlpha, f), accent = BoxLook.accentArgb(rgb, edgeAlpha, f);
        float a = e * 1.5f;
        if (bytes == null) bytes = new ByteBufferBuilder(8192);
        if (faces == null) faces = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        if (lines == null) lines = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        Matrix4f m = new Matrix4f(modelView).translate((float) (box.minX() - cam.x), (float) (box.minY() - cam.y), (float) (box.minZ() - cam.z));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        BufferBuilder q = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float[] quads = BoxLook.faceQuads(x0, y0, z0, x1, y1, z1);
        for (int side = 0; side < 6; side++) {
            int col = side == face ? lookedColor : faceColor;
            for (int i = side * 12, end = i + 12; i < end; i += 3) q.addVertex(quads[i], quads[i + 1], quads[i + 2]).setColor(col);
        }
        draw(faces, q.build(), m, projection, shader);

        BufferBuilder l = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        segments(l, BoxLook.edges(x0, y0, z0, x1, y1, z1), edge);
        RenderSystem.lineWidth(thick ? 3f : 2f);
        draw(lines, l.build(), m, projection, shader);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        segments(b, BoxLook.brackets(-a, -a, -a, box.sizeX() + a, box.sizeY() + a, box.sizeZ() + a), accent);
        RenderSystem.lineWidth(thick ? 4.5f : 3.5f);
        draw(lines, b.build(), m, projection, shader);
        VertexBuffer.unbind();

        RenderSystem.lineWidth(1f);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void draw(VertexBuffer vb, MeshData mesh, Matrix4f m, Matrix4f projection, ShaderInstance shader) {
        if (mesh == null) return;
        vb.bind();
        vb.upload(mesh);
        vb.drawWithShader(m, projection, shader);
    }

    private static void segments(BufferBuilder b, float[] s, int color) {
        for (int i = 0; i < s.length; i += 6) {
            b.addVertex(s[i], s[i + 1], s[i + 2]).setColor(color);
            b.addVertex(s[i + 3], s[i + 4], s[i + 5]).setColor(color);
        }
    }
}
