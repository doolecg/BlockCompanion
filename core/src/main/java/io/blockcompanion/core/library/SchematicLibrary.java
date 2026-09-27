package io.blockcompanion.core.library;

import io.blockcompanion.core.formats.SchematicFile;
import io.blockcompanion.core.formats.Schematics;
import io.blockcompanion.core.formats.WriteOptions;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.project.BdProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The schematics folder: lists the files it can read (subfolders included), loads them and saves new ones.
 *
 * <p>BlockDesigner projects ({@code .bdproj}) and Sponge schematics ({@code .schem}) are the main formats and are listed
 * first; Litematica ({@code .litematic}) and vanilla structures ({@code .nbt}) load too, for compatibility. Saving always
 * writes Sponge Schematic v3. See {@code docs/formats.md}.
 */
public final class SchematicLibrary {
    /** Extension every save uses. */
    public static final String SAVE_EXTENSION = "schem";

    private final Path root;

    public SchematicLibrary(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** What kind of file an entry is, in list order: the preferred formats first. */
    public enum Kind {
        PROJECT("BlockDesigner", true),
        SPONGE("Sponge", true),
        LITEMATICA("Litematica", false),
        VANILLA("Structure", false),
        OTHER("", false);

        private final String label;
        private final boolean preferred;

        Kind(String label, boolean preferred) {
            this.label = label;
            this.preferred = preferred;
        }

        /** A short label for the list. */
        public String label() {
            return label;
        }

        /** BlockDesigner's own formats: {@code .bdproj} and {@code .schem}. */
        public boolean preferred() {
            return preferred;
        }

        public static Kind of(String fileName) {
            String n = fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".bdproj")) return PROJECT;
            if (n.endsWith(".schem") || n.endsWith(".schematic")) return SPONGE;
            if (n.endsWith(".litematic")) return LITEMATICA;
            if (n.endsWith(".nbt")) return VANILLA;
            return OTHER;
        }
    }

    /** A schematic in the library. {@code name} is its path relative to the library root, with forward slashes. */
    public record Entry(String name, Path path, long size) {
        public Kind kind() {
            return Kind.of(name);
        }

        public boolean preferred() {
            return kind().preferred();
        }

        public boolean isProject() {
            return kind() == Kind.PROJECT;
        }
    }

    /**
     * Creates the folder if needed and lists every readable schematic: {@code .bdproj} first, then {@code .schem}, then
     * the compatibility formats, each group sorted by name.
     */
    public List<Entry> list() throws IOException {
        Files.createDirectories(root);
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, 8)) {
            files.filter(Files::isRegularFile).filter(SchematicLibrary::isSchematic).forEach(p -> {
                long size;
                try {
                    size = Files.size(p);
                } catch (IOException e) {
                    size = 0;
                }
                out.add(new Entry(root.relativize(p).toString().replace('\\', '/'), p, size));
            });
        }
        out.sort(Comparator.comparing((Entry e) -> e.kind().ordinal()).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public static boolean isSchematic(Path p) {
        return Kind.of(p.getFileName().toString()) != Kind.OTHER;
    }

    /** Resolves a library-relative name, refusing names that escape the library folder. */
    public Path resolve(String name) throws IOException {
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root.normalize())) throw new IOException("Outside the schematic library: " + name);
        return p;
    }

    /** Loads a schematic by library-relative name: all its regions (or visible layers) merged, bounds at the origin. */
    public Structure load(String name) throws IOException {
        return loadFile(resolve(name));
    }

    /**
     * Loads any library file as one structure with its bounds starting at the origin. A BlockDesigner project gives its
     * visible layers merged in project coordinates, each with its own offset, rotation and mirror.
     */
    public static Structure loadFile(Path file) throws IOException {
        Structure s;
        if (BdProject.isProject(file)) {
            s = BdProject.read(file).mergeVisible();
        } else {
            SchematicFile sf = Schematics.read(file);
            // merged() may hand back the region's own structure; copy before normalising so nothing shared changes.
            s = sf.merged().copy();
        }
        s.normalizeToOrigin();
        return s;
    }

    // ---- saving ---------------------------------------------------------------------------------------------------

    /**
     * Turns what a player typed into a file name in the library root: characters Windows or the library can't take
     * become {@code _}, and {@code .schem} is added. Returns null if nothing usable is left.
     */
    public static String saveFileName(String typed) {
        if (typed == null) return null;
        String n = typed.strip();
        if (n.toLowerCase(Locale.ROOT).endsWith("." + SAVE_EXTENSION)) n = n.substring(0, n.length() - SAVE_EXTENSION.length() - 1);
        StringBuilder b = new StringBuilder();
        n.codePoints().forEach(c -> b.appendCodePoint(c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c));
        n = b.toString().strip();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).strip();
        while (n.startsWith(".")) n = n.substring(1).strip();
        if (n.length() > 80) n = n.substring(0, 80).strip();
        if (n.isEmpty() || n.chars().allMatch(c -> c == '_')) return null;
        return n + "." + SAVE_EXTENSION;
    }

    /** True if a save under this typed name would replace a file. */
    public boolean saveExists(String typed) throws IOException {
        String file = saveFileName(typed);
        return file != null && Files.exists(resolve(file));
    }

    /**
     * Saves blocks (with their block entities and entities) as a Sponge Schematic v3 file in the library root and
     * returns its library name. The schematic is trimmed to the blocks' bounds.
     *
     * @param typed       the name the player typed; see {@link #saveFileName}
     * @param dataVersion the game's DataVersion, written into the file
     * @param overwrite   replace an existing file of that name; otherwise an existing file is an error
     */
    public String save(String typed, Structure blocks, int dataVersion, String author, boolean overwrite) throws IOException {
        String file = saveFileName(typed);
        if (file == null) throw new IOException("Type a name for the schematic");
        if (blocks.blockCount() == 0) throw new IOException("There are no blocks to save");
        Path target = resolve(file);
        if (!overwrite && Files.exists(target)) throw new IOException(file + " already exists");
        Files.createDirectories(root);
        String name = file.substring(0, file.length() - SAVE_EXTENSION.length() - 1);
        Structure s = blocks.copy();
        s.normalizeToOrigin();
        SchematicFile sf = new SchematicFile(name, author, "", dataVersion, List.of(new SchematicFile.Region(name, s, BlockPos.ORIGIN)));
        Schematics.write(sf, Schematics.SPONGE, WriteOptions.defaults(dataVersion).withSpongeVersion(3), target);
        return file;
    }
}
