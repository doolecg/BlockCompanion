package io.blockcompanion.core.transform;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.nbt.CompoundTag;
import io.blockcompanion.core.nbt.FloatTag;
import io.blockcompanion.core.nbt.ListTag;

/**
 * Turns an entity with a structure transform, as BlockDesigner's {@code EntityTypes.transform} does: its position
 * (about block centres), yaw, a hanging entity's facing and the block it hangs from ({@code TileX/Y/Z}) all follow.
 */
public final class EntityTransform {
    private static final int[] FROM_2D = {3, 4, 2, 5}, TO_2D = {-1, -1, 2, 0, 1, 3};
    private static final int[][] FACING_VEC = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private EntityTransform() {
    }

    /** The entity turned by {@code t} and then moved by {@code (dx, dy, dz)}. */
    public static StructureEntity apply(StructureEntity e, Transform t, double dx, double dy, double dz) {
        double[] p = t.apply(e.x(), e.y(), e.z());
        CompoundTag nbt = e.nbt().copy();
        if (nbt.getList("Rotation").size() == 2) {
            ListTag rot = nbt.getList("Rotation");
            nbt.put("Rotation", ListTag.of(new FloatTag(t.applyYaw((float) rot.getDouble(0))), new FloatTag((float) rot.getDouble(1))));
        }
        if (hanging(e.id()) && (nbt.contains("Facing") || nbt.contains("facing"))) {
            int[] v = FACING_VEC[facing(nbt)];
            BlockPos turned = t.apply(v[0], v[1], v[2]);
            setFacing(nbt, facingOf(turned.x(), turned.y(), turned.z()));
        }
        if (nbt.contains("TileX")) {
            BlockPos tile = t.apply(nbt.getInt("TileX"), nbt.getInt("TileY"), nbt.getInt("TileZ"));
            nbt.putInt("TileX", tile.x() + (int) Math.round(dx)).putInt("TileY", tile.y() + (int) Math.round(dy))
                    .putInt("TileZ", tile.z() + (int) Math.round(dz));
        }
        return new StructureEntity(p[0] + dx, p[1] + dy, p[2] + dz, nbt);
    }

    static boolean hanging(String id) {
        return id.endsWith(":painting") || id.endsWith("item_frame") || id.endsWith(":leash_knot");
    }

    static int facing(CompoundTag nbt) {
        boolean painting = nbt.getString("id").endsWith(":painting") || nbt.getString("id").equals("painting");
        String key = nbt.contains("facing") ? "facing" : "Facing";
        if (!nbt.contains(key)) return 3;
        int v = nbt.getByte(key);
        return painting ? FROM_2D[Math.floorMod(v, 4)] : Math.clamp(v, 0, 5);
    }

    static void setFacing(CompoundTag nbt, int facing3d) {
        if (nbt.getString("id").endsWith(":painting")) {
            nbt.remove("Facing");
            nbt.putByte("facing", Math.max(0, TO_2D[facing3d]));
        } else {
            nbt.remove("facing");
            nbt.putByte("Facing", facing3d);
        }
    }

    static int facingOf(int x, int y, int z) {
        for (int i = 0; i < 6; i++) if (FACING_VEC[i][0] == x && FACING_VEC[i][1] == y && FACING_VEC[i][2] == z) return i;
        return 3;
    }
}
