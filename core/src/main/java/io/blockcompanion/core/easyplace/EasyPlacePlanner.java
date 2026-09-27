package io.blockcompanion.core.easyplace;

import io.blockcompanion.core.model.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plans the one right-click that makes vanilla placement produce a wanted block state in a cell, the way Litematica's
 * easy place does: the client sends an ordinary "use item on block" aimed at the target cell itself (air is
 * replaceable, so vanilla places right there, even in mid-air), with the clicked face, hit point and player rotation
 * chosen so the block's own placement rule gives the wanted facing, half, axis, hinge or rotation.
 *
 * <p>{@link #candidates} lists clicks best first: clicks this class's model of the vanilla rules ({@link #predict})
 * expects to be right come first. The game side then checks candidates against the real block's placement rule and
 * uses the first that matches, falling back to the first candidate. Properties no single click decides (stairs shape,
 * fence connections, chest pairing) follow from the neighbours, as they do for a player.
 */
public final class EasyPlacePlanner {
    private EasyPlacePlanner() {
    }

    /** The six directions, as in Minecraft. */
    public enum Dir {
        DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

        public final int dx, dy, dz;

        Dir(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean horizontal() {
            return dy == 0;
        }

        public char axis() {
            return dx != 0 ? 'x' : dy != 0 ? 'y' : 'z';
        }

        public Dir opposite() {
            return switch (this) {
                case DOWN -> UP;
                case UP -> DOWN;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }

        /** Clockwise seen from above (horizontal directions only). */
        public Dir clockwise() {
            return switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> this;
            };
        }

        /** Yaw of a player looking this way (south 0, west 90, north 180, east 270). */
        public float yaw() {
            return switch (this) {
                case SOUTH -> 0f;
                case WEST -> 90f;
                case NORTH -> 180f;
                case EAST -> 270f;
                default -> 0f;
            };
        }

        public static Dir parse(String s) {
            if (s == null) return null;
            for (Dir d : values()) if (d.id().equals(s)) return d;
            return null;
        }

        /** Minecraft's {@code Direction.fromYRot}: the horizontal direction a yaw faces. */
        public static Dir fromYaw(double yaw) {
            int i = Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4);
            return switch (i) {
                case 0 -> SOUTH;
                case 1 -> WEST;
                case 2 -> NORTH;
                default -> EAST;
            };
        }

        /** The direction a player with this yaw and pitch looks along most (Minecraft's nearest looking direction). */
        public static Dir nearestLooking(float yaw, float pitch) {
            double[] v = look(yaw, pitch);
            double ax = Math.abs(v[0]), ay = Math.abs(v[1]), az = Math.abs(v[2]);
            if (ay >= ax && ay >= az) return v[1] > 0 ? UP : DOWN;
            if (ax >= az) return v[0] > 0 ? EAST : WEST;
            return v[2] > 0 ? SOUTH : NORTH;
        }
    }

    /** The unit look vector for a yaw and pitch, as Minecraft computes it. */
    public static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /**
     * One click on the target cell: the face clicked, where on the cell (0..1 on each axis, on that face's plane),
     * the player's rotation while clicking, and whether to sneak.
     */
    public record Click(Dir face, double hitX, double hitY, double hitZ, float yaw, float pitch, boolean sneak) {
        /** A click on the middle of a face, with the hit point moved to {@code hitY} on horizontal faces. */
        public static Click on(Dir face, double u, double hitY, double w, float yaw, float pitch) {
            double x = u, y = hitY, z = w;
            switch (face) {
                case DOWN -> y = 0;
                case UP -> y = 1;
                case NORTH -> z = 0;
                case SOUTH -> z = 1;
                case WEST -> x = 0;
                case EAST -> x = 1;
            }
            return new Click(face, x, y, z, yaw, pitch, false);
        }

        public Dir horizontal() {
            return Dir.fromYaw(yaw);
        }
    }

    /** How a block works out its state from a click, for the blocks the model knows. */
    public enum Family {
        /** facing = player direction; half from the face and hit height. */
        STAIRS,
        /** type bottom/top from the face and hit height; a second click on a single slab makes a double. */
        SLAB,
        /** axis = the clicked face's axis. */
        PILLAR,
        /** facing = player direction, hinge from where on the cell you click; the upper half comes by itself. */
        DOOR,
        /** On a replaced cell: facing = opposite the player, half = bottom when clicking up. */
        TRAPDOOR,
        /** facing = player direction (fence gates, beds, decorated pots). */
        FACING_PLAYER,
        /** facing = towards the player (furnaces, chests, pumpkins, repeaters...). */
        FACING_AWAY,
        /** facing = player direction turned clockwise (anvils). */
        FACING_CLOCKWISE,
        /** facing = towards the player in 3D (pistons, dispensers, droppers, barrels). */
        LOOK_AWAY,
        /** facing = the way the player looks in 3D (observers). */
        LOOK,
        /** facing = the clicked face (end rods, lightning rods, amethyst, shulker boxes). */
        CLICKED_FACE,
        /** facing = away from the clicked face, down for top and bottom faces. */
        HOPPER,
        /** rotation 0-15 from the player's yaw plus a half turn (standing signs, banners). */
        ROTATION_FACING,
        /** rotation 0-15 from the player's yaw (skulls and heads). */
        ROTATION_YAW,
        /** face floor/ceiling/wall from the look direction; facing from it (buttons, levers, grindstones). */
        ATTACHED,
        /** On a wall: facing = away from the wall the player looks at (wall torches, wall signs, ladders). */
        WALL,
        /** No orientation the model knows about. */
        PLAIN
    }

    private static final Set<String> FACING_PLAYER = Set.of("decorated_pot");
    private static final Set<String> LOOK_AWAY = Set.of("piston", "sticky_piston", "dispenser", "dropper", "barrel", "command_block",
            "chain_command_block", "repeating_command_block");
    private static final Set<String> CLICKED_FACE = Set.of("end_rod", "lightning_rod", "amethyst_cluster", "small_amethyst_bud",
            "medium_amethyst_bud", "large_amethyst_bud");

    public static Family family(BlockState s) {
        String p = s.path();
        if (p.endsWith("_stairs")) return Family.STAIRS;
        if (p.endsWith("_slab")) return Family.SLAB;
        if (p.endsWith("_trapdoor")) return Family.TRAPDOOR;
        if (p.endsWith("_door")) return Family.DOOR;
        if (p.endsWith("_bed") || p.endsWith("_fence_gate") || FACING_PLAYER.contains(p)) return Family.FACING_PLAYER;
        if (p.equals("hopper")) return Family.HOPPER;
        if (s.has("axis")) return Family.PILLAR;
        if (s.has("face") && s.has("facing")) return Family.ATTACHED;
        if (s.has("rotation")) return p.endsWith("_skull") || p.endsWith("_head") ? Family.ROTATION_YAW : Family.ROTATION_FACING;
        if (p.equals("observer")) return Family.LOOK;
        if (LOOK_AWAY.contains(p)) return Family.LOOK_AWAY;
        if (CLICKED_FACE.contains(p) || p.endsWith("shulker_box")) return Family.CLICKED_FACE;
        if (p.endsWith("anvil")) return Family.FACING_CLOCKWISE;
        if (s.has("facing") && (p.contains("wall_") || p.equals("ladder") || p.equals("tripwire_hook"))) return Family.WALL;
        if (s.has("facing")) return Family.FACING_AWAY;
        return Family.PLAIN;
    }

    /**
     * What the model expects vanilla to make of a click on an empty (replaceable) cell: the orientation properties it
     * decides. {@code existing} is the block already in the cell (for a second slab click), or null.
     */
    public static Map<String, String> predict(Family f, Click c, BlockState existing) {
        Map<String, String> out = new TreeMap<>();
        Dir face = c.face();
        Dir player = c.horizontal();
        boolean upper = face != Dir.DOWN && (face == Dir.UP || !(c.hitY() > 0.5));
        switch (f) {
            case STAIRS -> {
                out.put("facing", player.id());
                out.put("half", upper ? "bottom" : "top");
            }
            case SLAB -> {
                if (existing != null && existing.path().endsWith("_slab") && !"double".equals(existing.get("type"))) {
                    boolean above = c.hitY() > 0.5;
                    boolean merges = "bottom".equals(existing.get("type"))
                            ? face == Dir.UP || (above && face.horizontal())
                            : face == Dir.DOWN || (!above && face.horizontal());
                    out.put("type", merges ? "double" : existing.get("type"));
                } else {
                    out.put("type", upper ? "bottom" : "top");
                }
            }
            case PILLAR -> out.put("axis", String.valueOf(face.axis()));
            case DOOR -> {
                out.put("facing", player.id());
                out.put("hinge", hinge(player, c.hitX(), c.hitZ()));
                out.put("half", "lower");
            }
            case TRAPDOOR -> {
                out.put("facing", player.opposite().id());
                out.put("half", face == Dir.UP ? "bottom" : "top");
            }
            case FACING_PLAYER -> out.put("facing", player.id());
            case FACING_AWAY -> out.put("facing", player.opposite().id());
            case FACING_CLOCKWISE -> out.put("facing", player.clockwise().id());
            case LOOK_AWAY -> out.put("facing", Dir.nearestLooking(c.yaw(), c.pitch()).opposite().id());
            case LOOK -> out.put("facing", Dir.nearestLooking(c.yaw(), c.pitch()).id());
            case CLICKED_FACE -> out.put("facing", face.id());
            case HOPPER -> out.put("facing", face.horizontal() ? face.opposite().id() : "down");
            case ROTATION_FACING -> out.put("rotation", Integer.toString(segment(c.yaw() + 180f)));
            case ROTATION_YAW -> out.put("rotation", Integer.toString(segment(c.yaw())));
            case ATTACHED -> {
                Dir look = Dir.nearestLooking(c.yaw(), c.pitch());
                if (look == Dir.UP || look == Dir.DOWN) {
                    out.put("face", look == Dir.UP ? "ceiling" : "floor");
                    out.put("facing", player.id());
                } else {
                    out.put("face", "wall");
                    out.put("facing", look.opposite().id());
                }
            }
            case WALL -> {
                Dir look = Dir.nearestLooking(c.yaw(), c.pitch());
                // The first horizontal direction in looking order: with a level look, the look direction itself.
                Dir h = look.horizontal() ? look : player;
                out.put("facing", h.opposite().id());
            }
            case PLAIN -> {
            }
        }
        return out;
    }

    /** Minecraft's {@code RotationSegment.convertToSegment}: 16 steps of 22.5 degrees. */
    public static int segment(float yaw) {
        return Math.floorMod((int) Math.floor(yaw * 16f / 360f + 0.5), 16);
    }

    /** The door hinge vanilla picks when no neighbouring door or wall decides it. */
    static String hinge(Dir facing, double hitX, double hitZ) {
        int i = facing.dx, j = facing.dz;
        boolean left = (i >= 0 || !(hitZ < 0.5)) && (i <= 0 || !(hitZ > 0.5)) && (j >= 0 || !(hitX > 0.5)) && (j <= 0 || !(hitX < 0.5));
        return left ? "left" : "right";
    }

    /** The orientation properties a single click decides, which the model checks against. */
    private static final Set<String> DECIDED = Set.of("facing", "half", "type", "axis", "hinge", "rotation", "face");

    /** True if the model expects the click to give the target's orientation. */
    public static boolean matches(BlockState target, Click c, BlockState existing) {
        Family f = family(target);
        Map<String, String> got = predict(f, c, existing);
        for (String prop : DECIDED) {
            String want = target.get(prop);
            if (want == null) continue;
            String have = got.get(prop);
            if (have != null && !have.equals(want)) return false;
        }
        return true;
    }

    /**
     * Candidate clicks for placing {@code target} into its cell, best first. {@code current} is the click the player
     * is making (their own face, hit point and rotation), tried first so a block that doesn't care needs no rotation;
     * it may be null. {@code existing} is what is in the cell now (air, or a single slab for a double), or null.
     * The list is never empty.
     */
    public static List<Click> candidates(BlockState target, Click current, BlockState existing) {
        Family f = family(target);
        Set<Click> all = new LinkedHashSet<>();
        if (current != null) all.add(current);
        float baseYaw = current != null ? current.yaw() : 0f;
        List<Float> yaws = new ArrayList<>();
        boolean orientable = f != Family.PLAIN || target.has("facing") || target.has("rotation");
        if (orientable) {
            for (int i = 0; i < 16; i++) yaws.add(i * 22.5f);
        } else {
            yaws.add(baseYaw);
        }
        float[] pitches = orientable ? new float[]{0f, 90f, -90f} : new float[]{current != null ? current.pitch() : 0f};
        double[][] hits = {{0.5, 0.25, 0.5}, {0.5, 0.75, 0.5}, {0.25, 0.25, 0.25}, {0.75, 0.25, 0.75}, {0.25, 0.25, 0.75},
                {0.75, 0.25, 0.25}};
        for (Dir face : Dir.values()) {
            for (float yaw : yaws) {
                for (float pitch : pitches) {
                    for (double[] h : hits) all.add(Click.on(face, h[0], h[1], h[2], yaw, pitch));
                }
            }
        }
        List<Click> good = new ArrayList<>(), rest = new ArrayList<>();
        for (Click c : all) (matches(target, c, existing) ? good : rest).add(c);
        // Among equally good clicks, prefer the player's own click, then the least turning, then the top face.
        Comparator<Click> byTurn = Comparator.comparingDouble(c -> turn(c, current));
        Comparator<Click> order = Comparator.<Click>comparingInt(c -> Objects.equals(c, current) ? 0 : 1).thenComparing(byTurn)
                .thenComparingInt(c -> c.face() == Dir.UP ? 0 : 1);
        good.sort(order);
        rest.sort(order);
        List<Click> out = new ArrayList<>(good.size() + rest.size());
        out.addAll(good);
        out.addAll(rest);
        return out;
    }

    /** The first candidate: the model's best guess. */
    public static Click best(BlockState target, Click current, BlockState existing) {
        return candidates(target, current, existing).get(0);
    }

    private static double turn(Click c, Click current) {
        if (current == null) return 0;
        double dy = Math.abs(Math.IEEEremainder(c.yaw() - current.yaw(), 360));
        return dy + Math.abs(c.pitch() - current.pitch());
    }
}
