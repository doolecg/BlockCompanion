package io.blockcompanion.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.Arrays;

/**
 * Records block-model and fluid vertices once (when a section is meshed) and replays them into the frame's buffers
 * each frame. Opacity is fixed and a slight cool tint marks ghosts as not built; the model's own shading, ambient
 * occlusion and the world light at the ghost's cell are kept, so ghosts look like real blocks lit like their
 * surroundings. Replaying can scale the colour and opacity (the slow pulse) and force full light (the "pop").
 */
final class QuadRecorder implements VertexConsumer {
    /** Per vertex: x, y, z, u, v, nx, ny, nz. */
    private float[] f = new float[8 * 256];
    /** Per vertex: colour (ARGB) and packed light. */
    private int[] c = new int[256];
    private int[] l = new int[256];
    private int count = -1;
    private final int alpha;
    private final float tintR, tintG, tintB;

    QuadRecorder(float alpha, boolean tint) {
        this.alpha = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        float[] t = tint ? GhostRenderer.tint() : new float[]{1f, 1f, 1f};
        this.tintR = t[0];
        this.tintG = t[1];
        this.tintB = t[2];
    }

    /** A frozen copy of what was recorded, or null when nothing was. */
    Recorded finish() {
        int n = count + 1;
        if (n == 0) return null;
        return new Recorded(Arrays.copyOf(f, n * 8), Arrays.copyOf(c, n), Arrays.copyOf(l, n), n);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        count++;
        if ((count + 1) * 8 > f.length) {
            f = Arrays.copyOf(f, f.length * 2);
            c = Arrays.copyOf(c, c.length * 2);
            l = Arrays.copyOf(l, l.length * 2);
        }
        int o = count * 8;
        f[o] = x;
        f[o + 1] = y;
        f[o + 2] = z;
        c[count] = (alpha << 24) | 0xFFFFFF;
        l[count] = 0xF000F0;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        c[count] = (alpha << 24) | (Math.round((r & 255) * tintR) << 16) | (Math.round((g & 255) * tintG) << 8) | Math.round((b & 255) * tintB);
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        return setColor((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24));
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        f[count * 8 + 3] = u;
        f[count * 8 + 4] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        l[count] = (u & 0xFFFF) | (v << 16);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        int o = count * 8;
        f[o + 5] = x;
        f[o + 6] = y;
        f[o + 7] = z;
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    /** Recorded vertices, positions relative to the section's (or the popped cell's) origin. */
    record Recorded(float[] f, int[] c, int[] l, int vertices) {
        private static final int FULL_BRIGHT = 0xF000F0;

        void replay(PoseStack.Pose pose, VertexConsumer out, float rgb, float alphaScale, boolean fullBright) {
            boolean plain = rgb == 1f && alphaScale == 1f;
            for (int i = 0; i < vertices; i++) {
                int o = i * 8;
                int col = c[i];
                if (!plain) {
                    int a = Math.min(255, Math.round((col >>> 24) * alphaScale));
                    int r = Math.min(255, Math.round(((col >> 16) & 255) * rgb));
                    int g = Math.min(255, Math.round(((col >> 8) & 255) * rgb));
                    int b = Math.min(255, Math.round((col & 255) * rgb));
                    col = (a << 24) | (r << 16) | (g << 8) | b;
                }
                out.addVertex(pose, f[o], f[o + 1], f[o + 2])
                        .setColor(col)
                        .setUv(f[o + 3], f[o + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(fullBright ? FULL_BRIGHT : l[i])
                        .setNormal(pose, f[o + 5], f[o + 6], f[o + 7]);
            }
        }
    }
}
