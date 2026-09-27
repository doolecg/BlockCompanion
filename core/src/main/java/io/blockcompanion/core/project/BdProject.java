package io.blockcompanion.core.project;

import io.blockcompanion.core.formats.SchematicFormat;
import io.blockcompanion.core.formats.Schematics;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.nbt.CompoundTag;
import io.blockcompanion.core.nbt.NbtIO;
import io.blockcompanion.core.transform.BlockTransformer;
import io.blockcompanion.core.transform.EntityTransform;
import io.blockcompanion.core.transform.Transform;
import io.blockcompanion.core.util.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A BlockDesigner project ({@code .bdproj}): a zip with {@code project.json} and one block file per layer. Read-only
 * here; the layout follows BlockDesigner's {@code ProjectFile}:
 * <ul>
 *   <li>format 2 stores each layer as {@code layers/<id>.schem} (Sponge Schematic v3), format 1 as
 *   {@code layers/<id>.litematic}; both are read, a higher format is refused;</li>
 *   <li>a layer's blocks are stored from their own min corner, which {@code origin} puts back, so they sit in the
 *   layer's local coordinates;</li>
 *   <li>a layer's world position is {@code transform(local) + offset}: the mirror first, then {@code rotation} clockwise
 *   quarter turns (seen from above) around the local origin, then the offset;</li>
 *   <li>other zip entries (plugins keep their data there) are ignored.</li>
 * </ul>
 */
public final class BdProject {
    public static final String EXTENSION = "bdproj";
    /** The newest project format this reader understands. */
    public static final int MAX_FORMAT = 2;
    /** Limits for a project read from the library or a server, so a hostile file can't exhaust memory. */
    private static final long MAX_ENTRY_BYTES = 256L << 20;

    private final int format;
    private final String name;
    private final String targetVersion;
    private final int dataVersion;
    private final String activeLayer;
    private final List<Layer> layers;

    /** One layer: its blocks in local coordinates and how they are placed in the project. */
    public record Layer(String id, String name, Structure structure, BlockPos offset, Transform transform, boolean visible,
                        boolean locked, boolean ghost, int color, String source) {

        public BlockPos toWorld(int x, int y, int z) {
            return transform.apply(x, y, z).add(offset);
        }

        public BlockPos toWorld(BlockPos local) {
            return toWorld(local.x(), local.y(), local.z());
        }

        /** Project-space bounds of this layer's blocks, if it has any. */
        public Optional<Box> worldBounds() {
            return structure.bounds().map(b -> Box.of(toWorld(b.min()), toWorld(b.max())));
        }
    }

    /** The project's name and layer list without any blocks, for showing in a list. */
    public record Info(int format, String name, String targetVersion, List<LayerInfo> layers) {
    }

    public record LayerInfo(String id, String name, boolean visible) {
    }

    private BdProject(int format, String name, String targetVersion, int dataVersion, String activeLayer, List<Layer> layers) {
        this.format = format;
        this.name = name;
        this.targetVersion = targetVersion;
        this.dataVersion = dataVersion;
        this.activeLayer = activeLayer;
        this.layers = List.copyOf(layers);
    }

    public int format() {
        return format;
    }

    public String name() {
        return name;
    }

    /** The Minecraft version the project targets, e.g. {@code 26.3}; may be empty. */
    public String targetVersion() {
        return targetVersion;
    }

    public int dataVersion() {
        return dataVersion;
    }

    /** Id of the layer that was active in BlockDesigner, or null. */
    public String activeLayer() {
        return activeLayer;
    }

    /** Every layer in project order (later layers are drawn over earlier ones), hidden ones included. */
    public List<Layer> layers() {
        return layers;
    }

    public List<Layer> visibleLayers() {
        return layers.stream().filter(Layer::visible).toList();
    }

    public static boolean isProject(Path file) {
        return file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + EXTENSION);
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    public static BdProject read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public static BdProject read(InputStream in) throws IOException {
        Map<String, byte[]> entries = unzip(in, null);
        byte[] pj = entries.remove("project.json");
        if (pj == null) throw new IOException("Not a BlockDesigner project (missing project.json)");
        Map<String, Object> root = parseProject(pj);
        int format = checkFormat(root);

        List<Layer> layers = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Object o : Json.array(root.get("layers"))) {
            Map<String, Object> n = Json.object(o);
            String entry = Json.string(n.get("file"), "");
            byte[] data = entries.get(entry);
            Structure s = new Structure();
            if (data != null) {
                CompoundTag nbt = NbtIO.read(new ByteArrayInputStream(data)).tag();
                // Format 2 layers are Sponge .schem; format 1 layers were Litematica. Same rule as BlockDesigner.
                SchematicFormat f = entry.toLowerCase(java.util.Locale.ROOT).endsWith(".litematic") ? Schematics.LITEMATICA : Schematics.SPONGE;
                Structure normalized = f.read(nbt).merged().copy();
                normalized.normalizeToOrigin();
                List<Object> origin = Json.array(n.get("origin"));
                s.paste(normalized, at(origin, 0), at(origin, 1), at(origin, 2));
                s.metadata().dataVersion = normalized.metadata().dataVersion;
            }
            String id = Json.string(n.get("id"), "");
            if (!used.add(id)) id = UUID.randomUUID().toString();
            List<Object> off = Json.array(n.get("offset"));
            Transform t = new Transform(Json.integer(n.get("rotation"), 0), mirror(Json.string(n.get("mirror"), "NONE")));
            layers.add(new Layer(id, Json.string(n.get("name"), "Layer"), s, new BlockPos(at(off, 0), at(off, 1), at(off, 2)), t,
                    Json.bool(n.get("visible"), true), Json.bool(n.get("locked"), false), Json.bool(n.get("ghost"), false),
                    color(Json.string(n.get("color"), "#7C9CFF")), Json.string(n.get("source"), null)));
        }
        return new BdProject(format, Json.string(root.get("name"), "Untitled"), Json.string(root.get("targetVersion"), ""),
                Json.integer(root.get("dataVersion"), 0), Json.string(root.get("activeLayer"), null), layers);
    }

    /** Reads only {@code project.json}: the name and the layers' names and visibility. Much cheaper than {@link #read}. */
    public static Info readInfo(Path file) throws IOException {
        Map<String, byte[]> entries;
        try (InputStream in = Files.newInputStream(file)) {
            entries = unzip(in, "project.json");
        }
        byte[] pj = entries.get("project.json");
        if (pj == null) throw new IOException("Not a BlockDesigner project (missing project.json)");
        Map<String, Object> root = parseProject(pj);
        int format = checkFormat(root);
        List<LayerInfo> layers = new ArrayList<>();
        for (Object o : Json.array(root.get("layers"))) {
            Map<String, Object> n = Json.object(o);
            layers.add(new LayerInfo(Json.string(n.get("id"), ""), Json.string(n.get("name"), "Layer"), Json.bool(n.get("visible"), true)));
        }
        return new Info(format, Json.string(root.get("name"), "Untitled"), Json.string(root.get("targetVersion"), ""), layers);
    }

    /** Zip entries by name; with {@code only} set, just that entry. Directories and names with {@code ..} are skipped. */
    private static Map<String, byte[]> unzip(InputStream in, String only) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().contains("..")) continue;
                if (only != null && !only.equals(e.getName())) continue;
                byte[] data = zip.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_ENTRY_BYTES + 1));
                if (data.length > MAX_ENTRY_BYTES) throw new IOException("Project entry too large: " + e.getName());
                entries.put(e.getName(), data);
                if (only != null) break;
            }
        } catch (java.util.zip.ZipException ex) {
            throw new IOException("Not a BlockDesigner project (not a zip file): " + ex.getMessage(), ex);
        }
        return entries;
    }

    private static Map<String, Object> parseProject(byte[] json) throws IOException {
        Object parsed = Json.parse(new String(json, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?>)) throw new IOException("project.json is not a JSON object");
        return Json.object(parsed);
    }

    private static int checkFormat(Map<String, Object> root) throws IOException {
        int format = Json.integer(root.get("format"), 1);
        if (format > MAX_FORMAT) {
            throw new IOException("This project was saved in BlockDesigner project format " + format
                    + ", newer than BlockCompanion reads (up to " + MAX_FORMAT + "). Update BlockCompanion, or export it as .schem.");
        }
        return format;
    }

    private static int at(List<Object> a, int i) {
        return i < a.size() ? Json.integer(a.get(i), 0) : 0;
    }

    private static Transform.Mirror mirror(String s) {
        try {
            return Transform.Mirror.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Transform.Mirror.NONE;
        }
    }

    private static int color(String s) {
        try {
            return Integer.parseInt(s.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return 0x7C9CFF;
        }
    }

    // ---- merging --------------------------------------------------------------------------------------------------

    /** The visible layers merged into one structure in project coordinates (see {@link #flatten}). */
    public Structure mergeVisible() {
        Structure s = flatten(visibleLayers());
        s.metadata().name = name;
        if (s.metadata().dataVersion == 0) s.metadata().dataVersion = dataVersion;
        return s;
    }

    /**
     * Merges layers (in order, later ones win) into one project-space structure, applying each layer's transform to
     * positions, block states, block entities and entities. A port of BlockDesigner's {@code Scene.flatten}.
     */
    public static Structure flatten(List<Layer> toMerge) {
        BlockTransformer bt = BlockTransformer.defaults();
        Structure out = new Structure();
        for (Layer layer : toMerge) {
            Structure s = layer.structure();
            Transform t = layer.transform();
            // The lattice transform is linear: world = M·local + offset.
            BlockPos ex = t.apply(1, 0, 0), ez = t.apply(0, 0, 1), off = layer.offset();
            int m00 = ex.x(), m01 = ez.x(), m10 = ex.z(), m11 = ez.z();
            int ox = off.x(), oy = off.y(), oz = off.z();
            IdentityHashMap<BlockState, BlockState> turned = new IdentityHashMap<>();
            s.forEachBlock((x, y, z, state) -> {
                BlockState w = turned.get(state);
                if (w == null) {
                    w = bt.apply(state, t);
                    turned.put(state, w);
                }
                out.set(m00 * x + m01 * z + ox, y + oy, m10 * x + m11 * z + oz, w);
            });
            for (var e : s.blockEntities().entrySet()) {
                BlockPos wp = layer.toWorld(e.getKey());
                if (out.get(wp).name().equals(s.get(e.getKey()).name())) out.setBlockEntity(wp, e.getValue().copy());
            }
            for (StructureEntity e : s.entities()) out.addEntity(EntityTransform.apply(e, t, ox, oy, oz));
        }
        if (!toMerge.isEmpty()) out.metadata().dataVersion = toMerge.getFirst().structure().metadata().dataVersion;
        return out;
    }
}
