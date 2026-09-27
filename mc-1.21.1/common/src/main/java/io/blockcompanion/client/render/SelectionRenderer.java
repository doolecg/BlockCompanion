package io.blockcompanion.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.model.Box;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Draws the save selection: an outline (cyan by default) around the marked box (one block while only the first corner is set). */
public final class SelectionRenderer {
    private static int color() {
        return BlockCompanionClient.config().colors.argb(Palette.Entry.SELECTION, 0xFF);
    }

    private VertexBuffer lines;
    private Box linesFor;
    private int linesColor;
    private ByteBufferBuilder bytes;

    public void clear() {
        if (lines != null) lines.close();
        lines = null;
        linesFor = null;
    }

    public void render(Box box, Matrix4f modelView, Matrix4f projection, Vec3 cam) {
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) return;
        int color = color();
        if (lines == null || !box.equals(linesFor) || color != linesColor) {
            if (lines != null) lines.close();
            if (bytes == null) bytes = new ByteBufferBuilder(4096);
            lines = new VertexBuffer(VertexBuffer.Usage.STATIC);
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            float e = 0.01f;
            float sx = box.sizeX() + e, sy = box.sizeY() + e, sz = box.sizeZ() + e;
            float[][] c = {{-e, -e, -e}, {sx, -e, -e}, {sx, -e, sz}, {-e, -e, sz}, {-e, sy, -e}, {sx, sy, -e}, {sx, sy, sz}, {-e, sy, sz}};
            int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] edge : edges) {
                b.addVertex(c[edge[0]][0], c[edge[0]][1], c[edge[0]][2]).setColor(color);
                b.addVertex(c[edge[1]][0], c[edge[1]][1], c[edge[1]][2]).setColor(color);
            }
            lines.bind();
            lines.upload(b.buildOrThrow());
            linesFor = box;
            linesColor = color;
        }
        Matrix4f m = new Matrix4f(modelView).translate((float) (box.minX() - cam.x), (float) (box.minY() - cam.y), (float) (box.minZ() - cam.z));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.lineWidth(2f);
        lines.bind();
        lines.drawWithShader(m, projection, shader);
        VertexBuffer.unbind();
        RenderSystem.lineWidth(1f);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }
}
