package io.blockcompanion.core.sync;

import java.io.IOException;
import java.util.UUID;

/**
 * A placement everyone on the server sees. Position and orientation mean exactly what they mean in the client's
 * {@code Placement}: the transformed box's minimum corner, clockwise quarter turns seen from above, and a mirror across
 * the schematic's own X axis applied before rotating. So every client that applies these numbers draws the same blocks.
 *
 * @param id         server-assigned id
 * @param hash       the schematic file (SHA-256 hex) it shows
 * @param name       the schematic's file name, for display
 * @param dimension  dimension id, e.g. {@code minecraft:overworld}
 * @param owner      who created it
 * @param locked     owner lock: only the owner and admins may move or delete it
 * @param editor     who holds the temporary editing lock (is moving it right now), or null
 * @param editorName their name, empty when nobody edits
 * @param revision   bumped on every change, so late updates can be told from new ones
 */
public record SharedPlacement(UUID id, String hash, String name, String dimension, int x, int y, int z, int rotation,
                              boolean mirrored, UUID owner, String ownerName, boolean locked, UUID editor,
                              String editorName, long revision) {

    public SharedPlacement {
        rotation = Math.floorMod(rotation, 4);
        if (editorName == null) editorName = "";
    }

    public PlacementPose pose() {
        return new PlacementPose(dimension, x, y, z, rotation, mirrored);
    }

    public SharedPlacement withPose(PlacementPose p) {
        return new SharedPlacement(id, hash, name, p.dimension(), p.x(), p.y(), p.z(), p.rotation(), p.mirrored(), owner,
                ownerName, locked, editor, editorName, revision + 1);
    }

    /** The same placement showing another file (a new version of its schematic). */
    public SharedPlacement withHash(String newHash) {
        return new SharedPlacement(id, newHash, name, dimension, x, y, z, rotation, mirrored, owner, ownerName, locked, editor, editorName,
                revision + 1);
    }

    public SharedPlacement withLocked(boolean newLocked) {
        return new SharedPlacement(id, hash, name, dimension, x, y, z, rotation, mirrored, owner, ownerName, newLocked, editor,
                editorName, revision + 1);
    }

    public SharedPlacement withEditor(UUID newEditor, String newEditorName) {
        return new SharedPlacement(id, hash, name, dimension, x, y, z, rotation, mirrored, owner, ownerName, locked, newEditor,
                newEditor == null ? "" : newEditorName, revision + 1);
    }

    void write(Wire.Out out) {
        out.uuid(id).hash(hash).string(name).string(dimension).i32(x).i32(y).i32(z).varInt(rotation).bool(mirrored)
                .uuid(owner).string(ownerName).bool(locked).optUuid(editor).string(editorName).varLong(revision);
    }

    static SharedPlacement read(Wire.In in) throws IOException {
        return new SharedPlacement(in.uuid(), in.hash(), in.string(), in.string(), in.i32(), in.i32(), in.i32(), in.varInt(),
                in.bool(), in.uuid(), in.string(), in.bool(), in.optUuid(), in.string(), in.varLong());
    }
}
