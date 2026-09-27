package io.blockcompanion.client.render;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.BoxLook;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.model.Box;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the save selection (cyan by default) around the marked box, one block while only the first corner is set,
 * in the {@link BoxRenderer} look.
 */
public final class SelectionRenderer {
    public void clear() {
        // Nothing kept between frames: the box is drawn fresh each frame.
    }

    /** {@code lookedAt}: the selection is the box looked at (scrolling moves it), so its face brightens and its outline thickens. */
    public void render(Box box, boolean lookedAt, Matrix4f modelView, Matrix4f projection, Vec3 cam, Vector3f look) {
        int rgb = BlockCompanionClient.config().colors.get(Palette.Entry.SELECTION);
        int face = lookedAt ? BoxRenderer.lookedFace(box, cam, look.x(), look.y(), look.z(), BlockCompanionClient.config().reach) : -1;
        BoxRenderer.render(box, rgb, BoxLook.EDGE_ALPHA, face, lookedAt, modelView, projection, cam);
    }
}
