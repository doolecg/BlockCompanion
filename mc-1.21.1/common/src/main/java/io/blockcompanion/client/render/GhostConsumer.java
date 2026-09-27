package io.blockcompanion.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Passes block-model vertices through with a fixed opacity and a slight cool tint that marks ghosts as not built. The
 * model's own shading and the world light at the ghost's cell (as the model renderer worked them out, ambient
 * occlusion included) are kept, so ghosts look like real blocks lit like their surroundings. {@code fullBright} forces
 * full light instead (for the short "pop" of a filled ghost, whose cell is now solid and dark).
 */
final class GhostConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final int alpha;
    private final float tintR, tintG, tintB;
    private final boolean fullBright;

    GhostConsumer(VertexConsumer delegate, float alpha, boolean tint, boolean fullBright) {
        this.delegate = delegate;
        this.alpha = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        float[] t = tint ? GhostRenderer.tint() : new float[]{1f, 1f, 1f};
        this.tintR = t[0];
        this.tintG = t[1];
        this.tintB = t[2];
        this.fullBright = fullBright;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        delegate.setColor(Math.round(r * tintR), Math.round(g * tintG), Math.round(b * tintB), alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        if (fullBright) delegate.setUv2(240, 240);
        else delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }
}
