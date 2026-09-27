package io.blockcompanion.core.sync;

import java.io.IOException;

/**
 * Where a placement sits and how it is turned (see {@link SharedPlacement} for what the numbers mean).
 *
 * @param dimension dimension id, e.g. {@code minecraft:overworld}
 */
public record PlacementPose(String dimension, int x, int y, int z, int rotation, boolean mirrored) {

    public PlacementPose {
        rotation = Math.floorMod(rotation, 4);
    }

    void write(Wire.Out out) {
        out.string(dimension).i32(x).i32(y).i32(z).varInt(rotation).bool(mirrored);
    }

    static PlacementPose read(Wire.In in) throws IOException {
        return new PlacementPose(in.string(), in.i32(), in.i32(), in.i32(), in.varInt(), in.bool());
    }
}
