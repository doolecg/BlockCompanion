package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;

/** Ray against an inclusive block box: whether the player is looking at the placement, and at which face. */
public final class RayBox {
    private RayBox() {
    }

    /**
     * Where a ray meets the box.
     *
     * @param distance how far along the ray (0 when the ray starts inside)
     * @param axis     0 = x, 1 = y, 2 = z: the axis of the face the ray enters through
     * @param sign     +1 if that face points to the positive side of the axis (e.g. the top face), -1 otherwise
     * @param inside   true if the ray starts inside the box; axis and sign then give the direction it mostly points in
     */
    public record Hit(double distance, int axis, int sign, boolean inside) {
        /**
         * How far {@code notches} scroll notches move a box looked at through this hit: along the axis of the face looked
         * at, away from the player for positive notches (into the face) and closer for negative ones. Standing inside,
         * along the way the player faces.
         */
        public BlockPos push(int notches) {
            int d = (inside ? sign : -sign) * notches;
            return new BlockPos(axis == 0 ? d : 0, axis == 1 ? d : 0, axis == 2 ? d : 0);
        }
    }

    /**
     * Whether box {@code a} is the one looked at rather than box {@code b} when the view ray meets both (a null hit
     * misses): a box seen from outside wins over one the player stands in, of two seen from outside the nearer wins, and
     * standing in both the smaller one wins. A tie goes to {@code b}.
     */
    public static boolean before(Hit a, long volumeA, Hit b, long volumeB) {
        if (a == null) return false;
        if (b == null) return true;
        if (a.inside() != b.inside()) return !a.inside();
        if (!a.inside()) return a.distance() < b.distance();
        return volumeA < volumeB;
    }

    /**
     * Intersects the ray {@code origin + t * dir} ({@code 0 <= t <= maxDistance}) with the box's volume, from its
     * minimum corner to one past its maximum. Returns null when it misses.
     */
    public static Hit intersect(double ox, double oy, double oz, double dx, double dy, double dz, Box box, double maxDistance) {
        double[] o = {ox, oy, oz}, d = {dx, dy, dz};
        double[] min = {box.minX(), box.minY(), box.minZ()}, max = {box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0};
        boolean inside = true;
        for (int i = 0; i < 3; i++) if (o[i] < min[i] || o[i] > max[i]) inside = false;
        if (inside) {
            int axis = dominantAxis(dx, dy, dz);
            return new Hit(0, axis, d[axis] >= 0 ? 1 : -1, true);
        }
        double tEnter = Double.NEGATIVE_INFINITY, tExit = Double.POSITIVE_INFINITY;
        int enterAxis = -1, enterSign = 0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < min[i] || o[i] > max[i]) return null;
                continue;
            }
            double t1 = (min[i] - o[i]) / d[i], t2 = (max[i] - o[i]) / d[i];
            // Entering through the min face means that face points to -axis.
            int sign = -1;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
                sign = 1;
            }
            if (t1 > tEnter) {
                tEnter = t1;
                enterAxis = i;
                enterSign = sign;
            }
            tExit = Math.min(tExit, t2);
        }
        if (enterAxis < 0 || tEnter > tExit || tExit < 0 || tEnter > maxDistance) return null;
        return new Hit(Math.max(0, tEnter), enterAxis, enterSign, false);
    }

    /** 0, 1 or 2 for whichever of x, y, z has the largest magnitude. */
    public static int dominantAxis(double dx, double dy, double dz) {
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return 1;
        return ax >= az ? 0 : 2;
    }
}
