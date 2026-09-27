package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * What is remembered about a placement between sessions, per world or server: which schematic, where, how it is turned,
 * the slice view, what is locked, whether it shows and whether it follows BlockDesigner. Stored as a small properties
 * file; {@link SavedPlacements} keeps one per loaded placement.
 *
 * @param file      schematic file name, relative to the schematic library
 * @param dimension dimension id the placement lives in, e.g. {@code minecraft:overworld}
 * @param level     slice level, or -1 for every level
 * @param locks     what can't be changed until unlocked
 * @param live      a project from BlockDesigner that reloads when the app sends a new version
 */
public record SavedPlacement(String file, String dimension, BlockPos origin, int rotation, boolean mirrored, int level, Layers.Mode mode,
                             Set<PlacementLock> locks, boolean visible, boolean live) {

    public SavedPlacement {
        locks = locks.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(locks));
    }

    /** Unlocked, shown, not live. */
    public SavedPlacement(String file, String dimension, BlockPos origin, int rotation, boolean mirrored, int level, Layers.Mode mode) {
        this(file, dimension, origin, rotation, mirrored, level, mode, Set.of(), true, false);
    }

    public static SavedPlacement of(Placement p, String dimension, Layers layers) {
        return of(p, dimension, layers, Set.of(), true, false);
    }

    public static SavedPlacement of(Placement p, String dimension, Layers layers, Set<PlacementLock> locks, boolean visible, boolean live) {
        return new SavedPlacement(p.name(), dimension, p.origin(), p.rotation(), p.mirrored(), layers.level(), layers.mode(), locks, visible, live);
    }

    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty("file", file);
        props.setProperty("dimension", dimension);
        props.setProperty("x", Integer.toString(origin.x()));
        props.setProperty("y", Integer.toString(origin.y()));
        props.setProperty("z", Integer.toString(origin.z()));
        props.setProperty("rotation", Integer.toString(rotation));
        props.setProperty("mirrored", Boolean.toString(mirrored));
        props.setProperty("level", Integer.toString(level));
        props.setProperty("mode", mode.name());
        props.setProperty("locks", String.join(",", locks.stream().map(l -> l.name().toLowerCase(Locale.ROOT)).sorted().toList()));
        props.setProperty("visible", Boolean.toString(visible));
        props.setProperty("live", Boolean.toString(live));
        return props;
    }

    /** Parses a saved placement; empty if the file entry is missing or a number is malformed. */
    public static Optional<SavedPlacement> fromProperties(Properties p) {
        String file = p.getProperty("file", "");
        if (file.isBlank()) return Optional.empty();
        try {
            BlockPos origin = new BlockPos(Integer.parseInt(p.getProperty("x", "0")), Integer.parseInt(p.getProperty("y", "0")),
                    Integer.parseInt(p.getProperty("z", "0")));
            Layers.Mode mode;
            try {
                mode = Layers.Mode.valueOf(p.getProperty("mode", "BUILD_UP"));
            } catch (IllegalArgumentException e) {
                mode = Layers.Mode.BUILD_UP;
            }
            Set<PlacementLock> locks = EnumSet.noneOf(PlacementLock.class);
            for (String s : p.getProperty("locks", "").split(",")) PlacementLock.parse(s).ifPresent(locks::add);
            return Optional.of(new SavedPlacement(file, p.getProperty("dimension", "minecraft:overworld"), origin,
                    Integer.parseInt(p.getProperty("rotation", "0")), Boolean.parseBoolean(p.getProperty("mirrored", "false")),
                    Integer.parseInt(p.getProperty("level", "-1")), mode, locks, !"false".equals(p.getProperty("visible", "true")),
                    Boolean.parseBoolean(p.getProperty("live", "false"))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void write(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            toProperties().store(w, "BlockCompanion placement");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static Optional<SavedPlacement> read(Path source) {
        if (!Files.isRegularFile(source)) return Optional.empty();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return Optional.empty();
        }
        return fromProperties(p);
    }

    /** A file-name-safe key for a world or server name (letters, digits, dot, dash and underscore kept). */
    public static String safeKey(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.length() > 80) s = s.substring(0, 80);
        return s.isEmpty() ? "default" : s;
    }
}
