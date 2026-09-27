package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.transform.BlockTransformer;
import io.blockcompanion.core.transform.Transform;

/**
 * A schematic placed in the world: its blocks (local coordinates, min corner at the origin), where its box starts, and
 * how it is turned. The local structure is mirrored first (across its X axis), then rotated clockwise in quarter turns
 * seen from above; the transformed box's minimum corner sits at {@link #origin()}.
 *
 * <p>Every change bumps {@link #version()}, which the renderer uses to know when meshes are stale.
 */
public final class Placement {
    private final String name;
    private final Structure structure;
    private final int sizeX, sizeY, sizeZ;
    private BlockPos origin;
    private int rotation;
    private boolean mirrored;
    private long version;
    // Derived from rotation and mirror.
    private Transform transform;
    private Transform inverse;
    private BlockPos shift;

    /**
     * @param name      the schematic's file name in the library (used to save and restore the placement)
     * @param structure blocks with their bounds starting at the origin (see {@link Structure#normalizeToOrigin()})
     */
    public Placement(String name, Structure structure, BlockPos origin) {
        this.name = name;
        this.structure = structure;
        Box b = structure.bounds().orElse(new Box(0, 0, 0, 0, 0, 0));
        if (!b.min().equals(BlockPos.ORIGIN)) {
            structure.normalizeToOrigin();
            b = structure.bounds().orElse(new Box(0, 0, 0, 0, 0, 0));
        }
        this.sizeX = b.sizeX();
        this.sizeY = b.sizeY();
        this.sizeZ = b.sizeZ();
        this.origin = origin;
        recompute();
    }

    public String name() {
        return name;
    }

    public Structure structure() {
        return structure;
    }

    public BlockPos origin() {
        return origin;
    }

    public int rotation() {
        return rotation;
    }

    public boolean mirrored() {
        return mirrored;
    }

    public long version() {
        return version;
    }

    public Transform transform() {
        return transform;
    }

    /** Size of the untransformed schematic. */
    public int localSizeX() {
        return sizeX;
    }

    public int localSizeY() {
        return sizeY;
    }

    public int localSizeZ() {
        return sizeZ;
    }

    /** The placed box in world coordinates (inclusive). */
    public Box worldBox() {
        boolean swap = (rotation & 1) == 1;
        return Box.ofSize(origin.x(), origin.y(), origin.z(), swap ? sizeZ : sizeX, sizeY, swap ? sizeX : sizeZ);
    }

    private void recompute() {
        transform = new Transform(rotation, mirrored ? Transform.Mirror.X : Transform.Mirror.NONE);
        inverse = transform.inverse();
        // Where the local box corners land; the smallest becomes the shift that puts the box's min corner at origin.
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int cx : new int[]{0, sizeX - 1}) {
            for (int cz : new int[]{0, sizeZ - 1}) {
                BlockPos p = transform.apply(cx, 0, cz);
                minX = Math.min(minX, p.x());
                minZ = Math.min(minZ, p.z());
            }
        }
        shift = new BlockPos(minX, 0, minZ);
    }

    public BlockPos toWorld(int x, int y, int z) {
        BlockPos p = transform.apply(x, y, z);
        return new BlockPos(origin.x() + p.x() - shift.x(), origin.y() + p.y(), origin.z() + p.z() - shift.z());
    }

    public BlockPos toLocal(int wx, int wy, int wz) {
        return inverse.apply(wx - origin.x() + shift.x(), wy - origin.y(), wz - origin.z() + shift.z());
    }

    /** The block the schematic wants at a world position, turned to match the placement; air outside the box. */
    public BlockState stateAt(int wx, int wy, int wz) {
        if (!worldBox().contains(wx, wy, wz)) return BlockState.AIR;
        BlockPos l = toLocal(wx, wy, wz);
        BlockState s = structure.get(l.x(), l.y(), l.z());
        return s.isAir() ? s : BlockTransformer.defaults().apply(s, transform);
    }

    /** True if the local position holds a block, without transforming its state. */
    public boolean hasBlockAt(int wx, int wy, int wz) {
        if (!worldBox().contains(wx, wy, wz)) return false;
        BlockPos l = toLocal(wx, wy, wz);
        return structure.has(l.x(), l.y(), l.z());
    }

    // ---- editing ------------------------------------------------------------------------------------------------

    public void moveTo(BlockPos newOrigin) {
        if (newOrigin.equals(origin)) return;
        origin = newOrigin;
        version++;
    }

    public void move(int dx, int dy, int dz) {
        moveTo(origin.add(dx, dy, dz));
    }

    /**
     * Turns the placement by {@code quarterTurns} clockwise (seen from above), keeping the box's centre where it was.
     */
    public void rotate(int quarterTurns) {
        int steps = Math.floorMod(quarterTurns, 4);
        if (steps == 0) return;
        keepCentre(() -> rotation = (rotation + steps) & 3);
    }

    /** Mirrors the schematic across its own X axis (east and west swap before rotating), keeping the box in place. */
    public void toggleMirror() {
        keepCentre(() -> mirrored = !mirrored);
    }

    /** Sets rotation and mirror directly (restoring a saved placement): the origin stays as given. */
    public void setOrientation(int rotation, boolean mirrored) {
        this.rotation = Math.floorMod(rotation, 4);
        this.mirrored = mirrored;
        recompute();
        version++;
    }

    private void keepCentre(Runnable change) {
        Box before = worldBox();
        // Doubled centre keeps the arithmetic in integers.
        int cx2 = before.minX() + before.maxX(), cz2 = before.minZ() + before.maxZ();
        change.run();
        recompute();
        Box after = worldBox();
        int nx = Math.floorDiv(cx2 - (after.sizeX() - 1), 2);
        int nz = Math.floorDiv(cz2 - (after.sizeZ() - 1), 2);
        origin = new BlockPos(nx, origin.y(), nz);
        version++;
    }
}
