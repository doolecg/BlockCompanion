package io.blockcompanion.core.progress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shared progress file that BlockDesigner's BlockCompanion Plugin reads (see {@code docs/progress-format.md}):
 * {@code <user home>/.blockcompanion/progress/<name>-<hash12>.json}, written atomically.
 */
public final class ProgressFile {
    public static final int FORMAT = 1;

    private ProgressFile() {
    }

    /**
     * What one file holds.
     *
     * @param schematic   the schematic's file name, e.g. {@code Watchtower.bdproj}
     * @param sha256      lowercase hex SHA-256 of the schematic file's bytes
     * @param projectName a project's own name (from its {@code project.json}); the file name without extension otherwise
     * @param world       the singleplayer world folder name, or the server address
     * @param items       item id to needed / placed, for the whole schematic
     */
    public record Snapshot(String schematic, String sha256, String projectName, String world, Instant updated, long total, long correct,
                           long wrong, long missing, Map<String, ProgressTracker.ItemProgress> items) {
    }

    /** {@code <user home>/.blockcompanion/progress}. */
    public static Path defaultFolder() {
        return Path.of(System.getProperty("user.home"), ".blockcompanion", "progress");
    }

    /** The file name without extension, every character outside {@code [A-Za-z0-9._-]} replaced by {@code _}. */
    public static String sanitize(String schematicFileName) {
        String n = schematicFileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        StringBuilder b = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            b.append(ok ? c : '_');
        }
        return b.isEmpty() ? "_" : b.toString();
    }

    /** {@code <name>-<first 12 hex of sha256>.json}. */
    public static String fileName(String schematicFileName, String sha256) {
        return sanitize(schematicFileName) + "-" + sha256.substring(0, 12).toLowerCase(java.util.Locale.ROOT) + ".json";
    }

    /** The file name without extension, for schematics that aren't projects. */
    public static String baseName(String schematicFileName) {
        String n = schematicFileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public static String toJson(Snapshot s) {
        StringBuilder b = new StringBuilder(256 + s.items().size() * 64);
        b.append("{\n");
        b.append("  \"format\": ").append(FORMAT).append(",\n");
        b.append("  \"schematic\": ").append(quote(s.schematic())).append(",\n");
        b.append("  \"sha256\": ").append(quote(s.sha256())).append(",\n");
        b.append("  \"projectName\": ").append(quote(s.projectName())).append(",\n");
        b.append("  \"world\": ").append(quote(s.world())).append(",\n");
        b.append("  \"updated\": ").append(quote(s.updated().truncatedTo(ChronoUnit.SECONDS).toString())).append(",\n");
        b.append("  \"total\": ").append(s.total()).append(", \"correct\": ").append(s.correct())
                .append(", \"wrong\": ").append(s.wrong()).append(", \"missing\": ").append(s.missing()).append(",\n");
        b.append("  \"items\": {");
        boolean first = true;
        for (var e : new TreeMap<>(s.items()).entrySet()) {
            b.append(first ? "\n" : ",\n");
            first = false;
            b.append("    ").append(quote(e.getKey())).append(": { \"needed\": ").append(e.getValue().needed())
                    .append(", \"placed\": ").append(e.getValue().placed()).append(" }");
        }
        b.append(first ? "}\n" : "\n  }\n");
        b.append("}\n");
        return b.toString();
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * Writes the snapshot into {@code folder} (created if needed): first to {@code <file>.json.tmp}, which the reader
     * ignores, then moved over the real file in one step. Returns the file.
     */
    public static Path write(Path folder, Snapshot s) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve(fileName(s.schematic(), s.sha256()));
        Path tmp = folder.resolve(file.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(s), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /**
     * Decides when to write: at most every {@link #MIN_INTERVAL_MS} while progress changes, a heartbeat every
     * {@link #HEARTBEAT_MS} while nothing changes (so the app doesn't mark the build "not seen"), and at once on
     * {@link #flush}.
     */
    public static final class Writer {
        public static final long MIN_INTERVAL_MS = 2000;
        public static final long HEARTBEAT_MS = 60_000;

        public interface Sink {
            void write(Snapshot s) throws IOException;
        }

        private final Sink sink;
        private boolean dirty = true;
        private long lastWrite = Long.MIN_VALUE / 2;
        private IOException lastError;

        public Writer(Path folder) {
            this(s -> ProgressFile.write(folder, s));
        }

        public Writer(Sink sink) {
            this.sink = sink;
        }

        /** Progress changed since the last write. */
        public void changed() {
            dirty = true;
        }

        /** Call regularly (e.g. every tick); writes when due. Returns true if it wrote. */
        public boolean tick(long nowMillis, java.util.function.Supplier<Snapshot> snapshot) {
            long since = nowMillis - lastWrite;
            if ((dirty && since >= MIN_INTERVAL_MS) || since >= HEARTBEAT_MS) return writeNow(nowMillis, snapshot);
            return false;
        }

        /** Writes now if anything changed since the last write (on unload and quit). */
        public boolean flush(long nowMillis, java.util.function.Supplier<Snapshot> snapshot) {
            return dirty && writeNow(nowMillis, snapshot);
        }

        private boolean writeNow(long nowMillis, java.util.function.Supplier<Snapshot> snapshot) {
            lastWrite = nowMillis;
            dirty = false;
            try {
                sink.write(snapshot.get());
                lastError = null;
                return true;
            } catch (IOException e) {
                lastError = e;
                return false;
            }
        }

        /** The last write's error, or null if it worked. */
        public IOException lastError() {
            return lastError;
        }
    }
}
