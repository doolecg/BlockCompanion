package io.blockcompanion.core.project;

import io.blockcompanion.core.formats.Schematics;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.nbt.CompoundTag;
import io.blockcompanion.core.nbt.FloatTag;
import io.blockcompanion.core.nbt.ListTag;
import io.blockcompanion.core.nbt.NbtIO;
import io.blockcompanion.core.transform.BlockTransformer;
import io.blockcompanion.core.transform.Transform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BdProjectTest {
    @TempDir
    Path tmp;

    /**
     * BlockDesigner's own world-position rule, copied from its {@code Layer.toWorld} and {@code Transform.apply}
     * independently of the core's classes: mirror first, then clockwise quarter turns about the local origin, then the
     * offset.
     */
    static BlockPos appToWorld(int x, int y, int z, int rotation, String mirror, BlockPos offset) {
        if (mirror.equals("X")) x = -x;
        else if (mirror.equals("Z")) z = -z;
        for (int i = 0; i < Math.floorMod(rotation, 4); i++) {
            int nx = -z;
            z = x;
            x = nx;
        }
        return new BlockPos(x + offset.x(), y + offset.y(), z + offset.z());
    }

    static final BlockState STONE = BlockState.of("minecraft:stone");
    static final BlockState GLASS = BlockState.of("minecraft:glass");
    static final BlockState DIAMOND = BlockState.of("minecraft:diamond_block");

    /** Layer A: a few blocks, a chest with items and an armor stand, turned once clockwise and moved. */
    static Structure layerA() {
        Structure s = new Structure();
        s.set(5, 0, 5, STONE);
        s.set(5, 0, 4, STONE);
        s.set(6, 0, 5, BlockState.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        s.set(5, 1, 7, BlockState.parse("minecraft:chest[facing=east,type=left,waterlogged=false]"));
        s.setBlockEntity(new BlockPos(5, 1, 7), new CompoundTag().putString("id", "minecraft:chest")
                .put("Items", ListTag.of(new CompoundTag().putByte("Slot", 0).putString("id", "minecraft:diamond").putInt("count", 3))));
        s.addEntity(new StructureEntity(5.5, 1, 5.5, new CompoundTag().putString("id", "minecraft:armor_stand")
                .put("Rotation", ListTag.of(new FloatTag(0f), new FloatTag(0f)))));
        return s;
    }

    static ProjectWriter sample(int format) {
        ProjectWriter w = new ProjectWriter();
        w.format = format;
        w.add(new ProjectWriter.L("a", "Walls", layerA(), new BlockPos(100, 64, -20), new Transform(1, Transform.Mirror.NONE)));

        Structure b = new Structure();
        b.set(2, 3, 4, BlockState.parse("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
        b.set(3, 3, 4, BlockState.parse("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]"));
        w.add(new ProjectWriter.L("b", "Mirrored", b, BlockPos.ORIGIN, new Transform(0, Transform.Mirror.X)));

        // Hidden: would put diamond where layer A has stone.
        Structure c = new Structure();
        c.set(0, 0, 0, DIAMOND);
        w.add(new ProjectWriter.L("c", "Hidden", c, new BlockPos(96, 64, -15), Transform.IDENTITY, false, "#FF0000"));

        // Later layers win: glass over layer A's other stone. Stored away from the origin, so "origin" matters.
        Structure e = new Structure();
        e.set(10, 20, 30, GLASS);
        w.add(new ProjectWriter.L("e", "Glass", e, new BlockPos(85, 44, -45), Transform.IDENTITY));
        return w;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void readsBothFormatsAndPlacesLayersLikeBlockDesigner(int format) throws IOException {
        Path file = tmp.resolve("p" + format + ".bdproj");
        sample(format).write(file);

        BdProject p = BdProject.read(file);
        assertThat(p.format()).isEqualTo(format);
        assertThat(p.name()).isEqualTo("Test project");
        assertThat(p.targetVersion()).isEqualTo("1.21.1");
        assertThat(p.activeLayer()).isEqualTo("a");
        assertThat(p.layers()).extracting(BdProject.Layer::name).containsExactly("Walls", "Mirrored", "Hidden", "Glass");
        assertThat(p.layers()).extracting(BdProject.Layer::visible).containsExactly(true, true, false, true);
        assertThat(p.layers().get(2).color()).isEqualTo(0xFF0000);
        assertThat(p.layers().get(0).transform()).isEqualTo(new Transform(1, Transform.Mirror.NONE));
        // Blocks come back in the layer's local coordinates (restored from "origin").
        assertThat(p.layers().get(3).structure().get(10, 20, 30)).isEqualTo(GLASS);

        Structure m = p.mergeVisible();
        // Worked out by hand: (x, z) turns clockwise to (-z, x), then the offset (100, 64, -20).
        assertThat(m.get(95, 64, -14).name()).isEqualTo("minecraft:oak_stairs");
        assertThat(m.get(95, 64, -14).get("facing")).isEqualTo("east");
        assertThat(m.get(96, 64, -15)).as("hidden layer skipped").isEqualTo(STONE);
        assertThat(m.get(95, 64, -15)).as("later layer wins").isEqualTo(GLASS);
        BlockState chest = m.get(93, 65, -15);
        assertThat(chest.get("facing")).isEqualTo("south");
        assertThat(chest.get("type")).isEqualTo("left");
        assertThat(m.blockEntity(new BlockPos(93, 65, -15)).getString("id")).isEqualTo("minecraft:chest");
        // Mirror X: x -> -x, east <-> west, and handedness flips.
        assertThat(m.get(-2, 3, 4).get("facing")).isEqualTo("west");
        assertThat(m.get(-3, 3, 4).get("facing")).isEqualTo("north");
        assertThat(m.get(-3, 3, 4).get("shape")).isEqualTo("inner_right");
        assertThat(m.get(95, 95, -15).isAir()).isTrue();
        assertThat(m.blockCount()).isEqualTo(4 + 2 + 1 - 1);

        // The armor stand turns about block centres, its yaw with it.
        assertThat(m.entities()).hasSize(1);
        StructureEntity stand = m.entities().getFirst();
        assertThat(new double[]{stand.x(), stand.y(), stand.z()}).containsExactly(95.5, 65.0, -14.5);
        assertThat(stand.yaw()).isEqualTo(90f);

        // Every block of every visible layer lands where BlockDesigner's own rule puts it (unless a later layer covers it).
        for (int li = 0; li < p.layers().size(); li++) {
            BdProject.Layer layer = p.layers().get(li);
            if (!layer.visible()) continue;
            int later = li;
            layer.structure().forEachBlock((x, y, z, s) -> {
                BlockPos w = appToWorld(x, y, z, layer.transform().rotation(), layer.transform().mirror().name(), layer.offset());
                if (coveredLater(p, later, w)) return;
                assertThat(m.get(w)).isEqualTo(BlockTransformer.defaults().apply(s, layer.transform()));
            });
        }
    }

    private static boolean coveredLater(BdProject p, int index, BlockPos w) {
        for (int i = index + 1; i < p.layers().size(); i++) {
            BdProject.Layer l = p.layers().get(i);
            if (!l.visible()) continue;
            BlockPos local = l.transform().inverse().apply(w.subtract(l.offset()));
            if (l.structure().has(local.x(), local.y(), local.z())) return true;
        }
        return false;
    }

    /** A project saved by BlockDesigner (format 1, one Litematica layer), copied from a real file. */
    @Test
    void readsARealFormatOneProject() throws IOException {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/projects/house-format1.bdproj")) {
            assertThat(in).isNotNull();
            bytes = in.readAllBytes();
        }
        BdProject p = BdProject.read(new ByteArrayInputStream(bytes));
        assertThat(p.format()).isEqualTo(1);
        assertThat(p.targetVersion()).isEqualTo("26.3");
        assertThat(p.dataVersion()).isEqualTo(5023);
        assertThat(p.layers()).hasSize(1);
        BdProject.Layer layer = p.layers().getFirst();
        assertThat(layer.name()).isEqualTo("Layer 1");
        assertThat(layer.offset()).isEqualTo(new BlockPos(-1, 16, -10));
        assertThat(layer.transform()).isEqualTo(Transform.IDENTITY);

        // The raw Litematica entry, normalised: each block must sit at local + origin (-18, -2, -4) + offset (-1, 16, -10).
        Structure raw = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().endsWith(".litematic")) raw = Schematics.LITEMATICA.read(NbtIO.read(new ByteArrayInputStream(zip.readAllBytes())).tag()).merged().copy();
            }
        }
        assertThat(raw).isNotNull();
        raw.normalizeToOrigin();
        assertThat(raw.blockCount()).isPositive();
        Structure m = p.mergeVisible();
        assertThat(m.blockCount()).isEqualTo(raw.blockCount());
        Structure rawF = raw;
        int[] checked = {0};
        rawF.forEachBlock((x, y, z, s) -> {
            BlockPos w = appToWorld(x - 18, y - 2, z - 4, 0, "NONE", new BlockPos(-1, 16, -10));
            assertThat(m.get(w)).isEqualTo(s);
            checked[0]++;
        });
        assertThat(checked[0]).isEqualTo(raw.blockCount());
        System.out.printf("house.bdproj: %d blocks, bounds %s%n", m.blockCount(), m.bounds().orElseThrow());
    }

    @Test
    void newerFormatIsAClearError() throws IOException {
        ProjectWriter w = sample(2);
        w.format = 3;
        Path file = tmp.resolve("future.bdproj");
        w.write(file);
        assertThatThrownBy(() -> BdProject.read(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("format 3").hasMessageContaining("newer");
        assertThatThrownBy(() -> BdProject.readInfo(file)).isInstanceOf(IOException.class).hasMessageContaining("format 3");
    }

    @Test
    void extraEntriesAreIgnoredAndInfoIsCheap() throws IOException {
        ProjectWriter w = sample(2);
        w.extras.put("plugins/resource-tracker/state.json", "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        w.extras.put("layers/not-a-layer.schem", new byte[]{1, 2, 3});
        Path file = tmp.resolve("extras.bdproj");
        w.write(file);
        assertThat(BdProject.read(file).layers()).hasSize(4);

        BdProject.Info info = BdProject.readInfo(file);
        assertThat(info.format()).isEqualTo(2);
        assertThat(info.name()).isEqualTo("Test project");
        assertThat(info.layers()).extracting(BdProject.LayerInfo::name).containsExactly("Walls", "Mirrored", "Hidden", "Glass");
        assertThat(info.layers()).extracting(BdProject.LayerInfo::visible).containsExactly(true, true, false, true);
    }

    @Test
    void notAProjectIsAnError() throws IOException {
        Path junk = tmp.resolve("junk.bdproj");
        Files.write(junk, new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> BdProject.read(junk)).isInstanceOf(IOException.class).hasMessageContaining("Not a BlockDesigner project");
    }

    /** The library loads a project as one structure (visible layers merged) with its bounds at the origin. */
    @Test
    void libraryLoadsAProjectAsOnePlacement() throws IOException {
        Path file = tmp.resolve("p.bdproj");
        sample(2).write(file);
        Structure s = SchematicLibrary.loadFile(file);
        Structure merged = BdProject.read(file).mergeVisible();
        assertThat(s.bounds().orElseThrow().min()).isEqualTo(BlockPos.ORIGIN);
        assertThat(s.blockCount()).isEqualTo(merged.blockCount());
        BlockPos min = merged.bounds().orElseThrow().min();
        merged.forEachBlock((x, y, z, st) -> assertThat(s.get(x - min.x(), y - min.y(), z - min.z())).isEqualTo(st));
    }

    /** A placed project answers the renderer, the compare and the resource list like any schematic. */
    @Test
    void placedProjectWorksWithCompareAndResources() throws IOException {
        Path file = tmp.resolve("p.bdproj");
        sample(2).write(file);
        io.blockcompanion.core.placement.Placement pl = new io.blockcompanion.core.placement.Placement("p.bdproj", SchematicLibrary.loadFile(file), new BlockPos(0, 64, 0));
        long placed = 0;
        io.blockcompanion.core.model.Box box = pl.worldBox();
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int y = box.minY(); y <= box.maxY(); y++)
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockState st = pl.stateAt(x, y, z);
                    if (st.isAir()) continue;
                    placed++;
                    assertThat(io.blockcompanion.core.compare.Compare.classify(st, BlockState.AIR)).isEqualTo(io.blockcompanion.core.compare.Compare.Result.MISSING);
                }
        assertThat(placed).isEqualTo(6);
        var needs = io.blockcompanion.core.items.ItemCount.count(pl, box, false);
        assertThat(needs).extracting(io.blockcompanion.core.items.ItemCount.Need::item)
                .contains("minecraft:stone", "minecraft:oak_stairs", "minecraft:chest", "minecraft:glass", "minecraft:armor_stand")
                .doesNotContain("minecraft:diamond_block");
    }
}
