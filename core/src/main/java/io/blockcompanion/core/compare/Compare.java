package io.blockcompanion.core.compare;

import io.blockcompanion.core.model.BlockState;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the world matches the schematic at one position. States are interned, so the common case (identical) is a
 * reference check; anything else is decided once per pair and cached.
 */
public final class Compare {
    public enum Result {
        /** Nothing wanted, nothing there (or only grass, water and similar clutter). */
        EMPTY,
        /** The right block is there. */
        CORRECT,
        /** A block is wanted and the spot is empty: shown as a faint ghost. */
        MISSING,
        /** A different block (or the right block turned the wrong way) is there: red. */
        WRONG,
        /** Something is there where the schematic has air: orange. */
        EXTRA
    }

    /** Blocks that count as empty space: placing over them is fine, and they are never "extra". */
    private static final Set<String> AIR_LIKE = Set.of("air", "cave_air", "void_air", "structure_void", "short_grass", "grass", "tall_grass",
            "fern", "large_fern", "dead_bush", "seagrass", "tall_seagrass", "water", "lava", "snow", "fire", "soul_fire", "bubble_column",
            "light");

    /**
     * Properties that decide whether a block of the right type is still "wrong": which way it faces or which half it
     * is. Everything else (connections, power, waterlogging, age...) follows from the world and is not checked.
     */
    private static final Set<String> SHAPE_PROPERTIES = Set.of("facing", "axis", "half", "type", "rotation", "hinge", "part", "face",
            "attachment", "orientation", "horizontal_facing", "vertical_direction", "hanging");

    private static final Map<Pair, Result> CACHE = new ConcurrentHashMap<>();

    private record Pair(BlockState expected, BlockState actual) {
    }

    private Compare() {
    }

    public static boolean isAirLike(BlockState s) {
        return s.isAir() || (s.namespace().equals("minecraft") && AIR_LIKE.contains(s.path()));
    }

    /** Compares what the schematic wants ({@code expected}) with what the world has ({@code actual}). */
    public static Result classify(BlockState expected, BlockState actual) {
        if (expected == actual) return expected.isAir() ? Result.EMPTY : Result.CORRECT;
        return CACHE.computeIfAbsent(new Pair(expected, actual), p -> compute(p.expected, p.actual));
    }

    private static Result compute(BlockState expected, BlockState actual) {
        boolean wantAir = expected.isAir();
        boolean haveAir = isAirLike(actual);
        if (wantAir) return haveAir ? Result.EMPTY : Result.EXTRA;
        if (!expected.name().equals(actual.name())) return haveAir ? Result.MISSING : Result.WRONG;
        for (String prop : SHAPE_PROPERTIES) {
            String want = expected.get(prop);
            if (want != null && actual.has(prop) && !Objects.equals(want, actual.get(prop))) return Result.WRONG;
        }
        return Result.CORRECT;
    }
}
