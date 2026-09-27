package io.blockcompanion.core.sync;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * The shared space on disk, one folder per world:
 * <pre>
 *   files/&lt;sha256&gt;.bin       the uploaded schematics, named by hash
 *   schematics.properties      name, size, uploader and time of each
 *   placements.properties      every shared placement (owner locks included; editing locks are not saved)
 * </pre>
 * Index files are rewritten whole (through a temporary file) on every change, which is fine at these sizes.
 */
public final class SharedStore {
    private final Path root;
    private final SyncLog log;
    private final Map<String, SchematicInfo> schematics = new LinkedHashMap<>();
    private final Map<UUID, SharedPlacement> placements = new LinkedHashMap<>();

    public SharedStore(Path root, SyncLog log) {
        this.root = root;
        this.log = log;
    }

    public Path root() {
        return root;
    }

    /** Reads the index files; entries whose file is missing are dropped. */
    public void load() throws IOException {
        Files.createDirectories(root.resolve("files"));
        schematics.clear();
        placements.clear();
        Properties s = read(root.resolve("schematics.properties"));
        for (String hash : ids(s)) {
            try {
                SchematicInfo info = new SchematicInfo(hash, s.getProperty(hash + ".name"), Long.parseLong(s.getProperty(hash + ".size")),
                        UUID.fromString(s.getProperty(hash + ".uploader")), s.getProperty(hash + ".uploaderName", "?"),
                        Long.parseLong(s.getProperty(hash + ".time", "0")));
                if (info.name() == null || !Hashes.isHash(hash) || !Files.isRegularFile(file(hash))) continue;
                schematics.put(hash, info);
            } catch (RuntimeException e) {
                log.warn("Skipping bad shared schematic entry " + hash + ": " + e);
            }
        }
        Properties p = read(root.resolve("placements.properties"));
        for (String id : ids(p)) {
            try {
                SharedPlacement sp = new SharedPlacement(UUID.fromString(id), p.getProperty(id + ".hash"), p.getProperty(id + ".name"),
                        p.getProperty(id + ".dimension", "minecraft:overworld"), Integer.parseInt(p.getProperty(id + ".x")),
                        Integer.parseInt(p.getProperty(id + ".y")), Integer.parseInt(p.getProperty(id + ".z")),
                        Integer.parseInt(p.getProperty(id + ".rotation", "0")), Boolean.parseBoolean(p.getProperty(id + ".mirrored")),
                        UUID.fromString(p.getProperty(id + ".owner")), p.getProperty(id + ".ownerName", "?"),
                        Boolean.parseBoolean(p.getProperty(id + ".locked")), null, "", Long.parseLong(p.getProperty(id + ".revision", "0")));
                if (!schematics.containsKey(sp.hash())) continue;
                placements.put(sp.id(), sp);
            } catch (RuntimeException e) {
                log.warn("Skipping bad shared placement entry " + id + ": " + e);
            }
        }
    }

    /** Entry ids: the part before the first dot of each key, in first-seen order. */
    private static List<String> ids(Properties p) {
        List<String> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot <= 0) continue;
            String id = key.substring(0, dot);
            if (!out.contains(id)) out.add(id);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    public Path file(String hash) {
        if (!Hashes.isHash(hash)) throw new IllegalArgumentException("Bad hash " + hash);
        return root.resolve("files").resolve(hash + ".bin");
    }

    public SchematicInfo schematic(String hash) {
        return schematics.get(hash);
    }

    public Collection<SchematicInfo> schematics() {
        return schematics.values();
    }

    public SharedPlacement placement(UUID id) {
        return placements.get(id);
    }

    public Collection<SharedPlacement> placements() {
        return placements.values();
    }

    public byte[] readFile(String hash) throws IOException {
        return Files.readAllBytes(file(hash));
    }

    /** Bytes uploaded by {@code player}. */
    public long usedBy(UUID player) {
        long total = 0;
        for (SchematicInfo s : schematics.values()) if (s.uploader().equals(player)) total += s.size();
        return total;
    }

    public long usedTotal() {
        long total = 0;
        for (SchematicInfo s : schematics.values()) total += s.size();
        return total;
    }

    public int placementsOwnedBy(UUID player) {
        int n = 0;
        for (SharedPlacement p : placements.values()) if (p.owner().equals(player)) n++;
        return n;
    }

    public void addSchematic(SchematicInfo info, byte[] data) throws IOException {
        Path f = file(info.hash());
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        schematics.put(info.hash(), info);
        saveSchematics();
    }

    public void removeSchematic(String hash) throws IOException {
        if (schematics.remove(hash) == null) return;
        Files.deleteIfExists(file(hash));
        saveSchematics();
    }

    public void putPlacement(SharedPlacement p) throws IOException {
        SharedPlacement before = placements.put(p.id(), p);
        // Editing-lock changes alone are not worth a write; they are not saved anyway.
        if (before != null && sameSaved(before, p)) return;
        savePlacements();
    }

    public void removePlacement(UUID id) throws IOException {
        if (placements.remove(id) != null) savePlacements();
    }

    private static boolean sameSaved(SharedPlacement a, SharedPlacement b) {
        return a.pose().equals(b.pose()) && a.locked() == b.locked() && a.owner().equals(b.owner());
    }

    private void saveSchematics() throws IOException {
        Properties p = new Properties();
        for (SchematicInfo s : schematics.values()) {
            String h = s.hash();
            p.setProperty(h + ".name", s.name());
            p.setProperty(h + ".size", Long.toString(s.size()));
            p.setProperty(h + ".uploader", s.uploader().toString());
            p.setProperty(h + ".uploaderName", s.uploaderName());
            p.setProperty(h + ".time", Long.toString(s.uploadedAt()));
        }
        write(root.resolve("schematics.properties"), p, "BlockCompanion shared schematics (files/<hash>.bin)");
    }

    private void savePlacements() throws IOException {
        Properties p = new Properties();
        for (SharedPlacement s : placements.values()) {
            String id = s.id().toString();
            p.setProperty(id + ".hash", s.hash());
            p.setProperty(id + ".name", s.name());
            p.setProperty(id + ".dimension", s.dimension());
            p.setProperty(id + ".x", Integer.toString(s.x()));
            p.setProperty(id + ".y", Integer.toString(s.y()));
            p.setProperty(id + ".z", Integer.toString(s.z()));
            p.setProperty(id + ".rotation", Integer.toString(s.rotation()));
            p.setProperty(id + ".mirrored", Boolean.toString(s.mirrored()));
            p.setProperty(id + ".owner", s.owner().toString());
            p.setProperty(id + ".ownerName", s.ownerName());
            p.setProperty(id + ".locked", Boolean.toString(s.locked()));
            p.setProperty(id + ".revision", Long.toString(s.revision()));
        }
        write(root.resolve("placements.properties"), p, "BlockCompanion shared placements");
    }

    private static Properties read(Path f) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return p;
    }

    private static void write(Path f, Properties p, String comment) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, comment);
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The schematic formats the shared space accepts, by file extension: BlockDesigner projects, Sponge, Litematica, vanilla. */
    public static boolean allowedName(String name) {
        if (name == null || name.isBlank() || name.length() > 128) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.chars().anyMatch(c -> c < 32)) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".bdproj") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".litematic") || n.endsWith(".nbt");
    }
}
