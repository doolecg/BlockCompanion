package io.blockcompanion.core.transform;

import io.blockcompanion.core.model.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Rotates and mirrors block states so that directional blocks (stairs, doors, rails, fences, signs...) keep facing the
 * right way after a structure transform. Ported from BlockDesigner, with its {@code transform_rules.json} built in so
 * the core needs no JSON library.
 */
public final class BlockTransformer {
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    private static final BlockTransformer DEFAULTS = new BlockTransformer();

    private final Set<String> directionProps = Set.of("facing", "horizontal_facing", "hopper_facing", "vertical_direction");
    private final Set<String> axisProps = Set.of("axis", "horizontal_axis");
    private final Set<String> rotation16Props = Set.of("rotation");
    private final List<String> sideKeys = List.of("north", "east", "south", "west");
    /** Properties whose values are made of direction tokens, and the blocks (globs) they apply to. */
    private final Map<String, List<Pattern>> tokenProps = Map.of(
            "orientation", List.of(glob("*")),
            "shape", List.of(glob("*rail*")));
    /** Mirroring flips handedness (stair corners, door hinges, double chests). */
    private final Map<String, Map<String, String>> mirrorSwaps = Map.of(
            "hinge", Map.of("left", "right", "right", "left"),
            "shape", Map.of("inner_left", "inner_right", "inner_right", "inner_left", "outer_left", "outer_right", "outer_right", "outer_left"),
            "type", Map.of("left", "right", "right", "left"));
    private final Set<String> rotationToggles = Set.of("axis_along_first");
    private final Map<CacheKey, BlockState> cache = new ConcurrentHashMap<>();

    private record CacheKey(BlockState state, Transform transform) {
    }

    private BlockTransformer() {
    }

    public static BlockTransformer defaults() {
        return DEFAULTS;
    }

    private static Pattern glob(String g) {
        return Pattern.compile(("\\Q" + g + "\\E").replace("*", "\\E.*\\Q"));
    }

    public BlockState apply(BlockState state, Transform t) {
        if (t.isIdentity() || state.properties().isEmpty()) return state;
        return cache.computeIfAbsent(new CacheKey(state, t), k -> compute(state, t));
    }

    private BlockState compute(BlockState state, Transform t) {
        Map<String, String> in = state.properties();
        Map<String, String> out = new TreeMap<>(in);

        // Side-keyed properties (fence/wall/pane/vine connections): move values between keys.
        Set<String> present = new HashSet<>();
        for (String k : sideKeys) if (in.containsKey(k)) present.add(k);
        if (!present.isEmpty()) {
            for (String k : present) out.remove(k);
            for (String k : present) out.put(dir(k, t), in.get(k));
        }

        for (var e : in.entrySet()) {
            String key = e.getKey(), value = e.getValue();
            if (sideKeys.contains(key)) continue;
            if (directionProps.contains(key) && HORIZONTAL.contains(value)) {
                out.put(key, dir(value, t));
            } else if (axisProps.contains(key) && (value.equals("x") || value.equals("z")) && t.rotation() % 2 == 1) {
                out.put(key, value.equals("x") ? "z" : "x");
            } else if (rotation16Props.contains(key) && isInt(value)) {
                out.put(key, Integer.toString(rotate16(Integer.parseInt(value), t)));
            } else if (rotationToggles.contains(key) && t.rotation() % 2 == 1 && (value.equals("true") || value.equals("false"))) {
                out.put(key, value.equals("true") ? "false" : "true");
            } else if (tokenProps.containsKey(key) && matches(tokenProps.get(key), state.name())) {
                out.put(key, rotateTokens(value, t));
            }
        }

        if (t.mirror() != Transform.Mirror.NONE) {
            for (var e : mirrorSwaps.entrySet()) {
                String v = out.get(e.getKey());
                if (v != null && e.getValue().containsKey(v)) out.put(e.getKey(), e.getValue().get(v));
            }
        }
        return state.withProperties(out);
    }

    private static boolean matches(List<Pattern> patterns, String name) {
        for (Pattern p : patterns) if (p.matcher(name).matches()) return true;
        return false;
    }

    private static boolean isInt(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    /** Transforms a horizontal direction name; non-horizontal names pass through. */
    public static String dir(String d, Transform t) {
        int i = HORIZONTAL.indexOf(d);
        if (i < 0) return d;
        if (t.mirror() == Transform.Mirror.X && (i == 1 || i == 3)) i = 4 - i;
        else if (t.mirror() == Transform.Mirror.Z && (i == 0 || i == 2)) i = 2 - i;
        return HORIZONTAL.get((i + t.rotation()) & 3);
    }

    /** 16-step rotation (0 = south, 4 = west, 8 = north, 12 = east). */
    static int rotate16(int r, Transform t) {
        if (t.mirror() == Transform.Mirror.X) r = (16 - r) & 15;
        else if (t.mirror() == Transform.Mirror.Z) r = (8 - r) & 15;
        return (r + 4 * t.rotation()) & 15;
    }

    /** Rotates each direction token in values like {@code ascending_east}, {@code south_west} or {@code north_up}. */
    private static String rotateTokens(String value, Transform t) {
        String[] parts = value.split("_");
        boolean any = false;
        for (int i = 0; i < parts.length; i++) {
            String r = dir(parts[i], t);
            if (!r.equals(parts[i])) any = true;
            parts[i] = r;
        }
        if (!any) return value;
        // Rail-style pairs of horizontal directions use a canonical order: north/south before east/west.
        if (parts.length == 2 && HORIZONTAL.contains(parts[0]) && HORIZONTAL.contains(parts[1])) {
            boolean ns0 = parts[0].equals("north") || parts[0].equals("south");
            boolean ns1 = parts[1].equals("north") || parts[1].equals("south");
            if (ns0 && ns1) return "north_south";
            if (!ns0 && !ns1) return "east_west";
            return ns0 ? parts[0] + "_" + parts[1] : parts[1] + "_" + parts[0];
        }
        return String.join("_", parts);
    }
}
