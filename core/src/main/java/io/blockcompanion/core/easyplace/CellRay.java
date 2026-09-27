package io.blockcompanion.core.easyplace;

/**
 * Walks a ray through the block grid cell by cell (Amanatides and Woo) and returns the first cell a test accepts:
 * finding the ghost block the player looks at, which may float in the air in front of real blocks.
 */
public final class CellRay {
    private CellRay() {
    }

    @FunctionalInterface
    public interface CellTest {
        boolean hit(int x, int y, int z);
    }

    /**
     * A hit: the cell, the face the ray entered it through, how far along the ray, and where (world coordinates).
     * When the ray starts inside the hit cell the face is the one facing back along the ray.
     */
    public record Hit(int x, int y, int z, EasyPlacePlanner.Dir face, double distance, double hx, double hy, double hz) {
    }

    /**
     * Casts from {@code (ox, oy, oz)} along {@code (dx, dy, dz)} (need not be normalised) for at most {@code maxDist}
     * blocks. Returns null when no accepted cell is that close.
     */
    public static Hit cast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist, CellTest test) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0 || maxDist <= 0) return null;
        dx /= len;
        dy /= len;
        dz /= len;
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tMaxX = boundary(ox, dx), tMaxY = boundary(oy, dy), tMaxZ = boundary(oz, dz);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        // The face looking back at the viewer when starting inside a cell: against the dominant direction.
        EasyPlacePlanner.Dir face = dominantBack(dx, dy, dz);
        double t = 0;
        while (t <= maxDist) {
            if (test.hit(x, y, z)) return new Hit(x, y, z, face, t, ox + dx * t, oy + dy * t, oz + dz * t);
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX;
                tMaxX += tDeltaX;
                x += stepX;
                face = stepX > 0 ? EasyPlacePlanner.Dir.WEST : EasyPlacePlanner.Dir.EAST;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY;
                tMaxY += tDeltaY;
                y += stepY;
                face = stepY > 0 ? EasyPlacePlanner.Dir.DOWN : EasyPlacePlanner.Dir.UP;
            } else {
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                z += stepZ;
                face = stepZ > 0 ? EasyPlacePlanner.Dir.NORTH : EasyPlacePlanner.Dir.SOUTH;
            }
        }
        return null;
    }

    private static double boundary(double o, double d) {
        if (d > 0) return (Math.floor(o) + 1 - o) / d;
        if (d < 0) return (o - Math.floor(o)) / -d;
        return Double.POSITIVE_INFINITY;
    }

    private static EasyPlacePlanner.Dir dominantBack(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? EasyPlacePlanner.Dir.DOWN : EasyPlacePlanner.Dir.UP;
        if (ax >= az) return dx > 0 ? EasyPlacePlanner.Dir.WEST : EasyPlacePlanner.Dir.EAST;
        return dz > 0 ? EasyPlacePlanner.Dir.NORTH : EasyPlacePlanner.Dir.SOUTH;
    }
}
