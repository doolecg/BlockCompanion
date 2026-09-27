package io.blockcompanion.core.library;

import io.blockcompanion.core.formats.SchematicFile;
import io.blockcompanion.core.formats.Schematics;
import io.blockcompanion.core.formats.WriteOptions;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.model.StructureEntity;
import io.blockcompanion.core.nbt.CompoundTag;
import io.blockcompanion.core.nbt.ListTag;
import io.blockcompanion.core.nbt.NbtIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchematicLibraryTest {
    @TempDir
    Path tmp;

    @Test
    void listsProjectsAndSpongeFirst() throws IOException {
        SchematicLibrary lib = new SchematicLibrary(tmp.resolve("schematics"));
        Files.createDirectories(lib.root().resolve("sub"));
        for (String n : new String[]{"a.nbt", "b.litematic", "c.schem", "Z.schem", "d.bdproj", "sub/e.bdproj", "notes.txt", "x.bdproj.tmp"}) {
            Files.write(lib.root().resolve(n), new byte[]{0});
        }
        var entries = lib.list();
        assertThat(entries).extracting(SchematicLibrary.Entry::name)
                .containsExactly("d.bdproj", "sub/e.bdproj", "c.schem", "Z.schem", "b.litematic", "a.nbt");
        assertThat(entries).extracting(SchematicLibrary.Entry::preferred).containsExactly(true, true, true, true, false, false);
        assertThat(entries.getFirst().isProject()).isTrue();
        assertThat(entries.getFirst().kind().label()).isEqualTo("BlockDesigner");
    }

    @Test
    void saveFileNames() {
        assertThat(SchematicLibrary.saveFileName("My Tower")).isEqualTo("My Tower.schem");
        assertThat(SchematicLibrary.saveFileName("  gate.SCHEM ")).isEqualTo("gate.schem");
        assertThat(SchematicLibrary.saveFileName("a/b\\c:d?")).isEqualTo("a_b_c_d_.schem");
        assertThat(SchematicLibrary.saveFileName("../../evil")).isEqualTo("_.._evil.schem");
        assertThat(SchematicLibrary.saveFileName("...")).isNull();
        assertThat(SchematicLibrary.saveFileName("   ")).isNull();
        assertThat(SchematicLibrary.saveFileName("///")).isNull();
        assertThat(SchematicLibrary.saveFileName("x".repeat(200))).hasSize(80 + 6);
    }

    /** What the game captures (world coordinates, a chest with items, an item frame) survives a save as Sponge v3. */
    @Test
    void saveWritesSpongeV3WithBlockEntitiesAndEntities() throws IOException {
        Structure world = new Structure();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) world.set(1000 + x, 70, -500 + z, BlockState.of("minecraft:stone_bricks"));
        world.set(1001, 71, -499, BlockState.parse("minecraft:chest[facing=west,type=single,waterlogged=false]"));
        world.setBlockEntity(new BlockPos(1001, 71, -499), new CompoundTag().putString("id", "minecraft:chest")
                .put("Items", ListTag.of(new CompoundTag().putByte("Slot", 3).putString("id", "minecraft:torch").putInt("count", 16))));
        world.addEntity(new StructureEntity(1002.5, 71.0, -498.5, new CompoundTag().putString("id", "minecraft:armor_stand")));

        SchematicLibrary lib = new SchematicLibrary(tmp.resolve("lib"));
        String name = lib.save("Gatehouse", world, WriteOptions.MC_1_21_1, "Alex", false);
        assertThat(name).isEqualTo("Gatehouse.schem");
        assertThat(lib.saveExists("Gatehouse")).isTrue();
        Path file = lib.resolve(name);

        // It really is Sponge v3: everything under "Schematic", Version 3, blocks in a "Blocks" compound.
        CompoundTag root = NbtIO.read(file).tag();
        CompoundTag body = root.getCompound("Schematic");
        assertThat(body.getInt("Version")).isEqualTo(3);
        assertThat(body.getInt("DataVersion")).isEqualTo(WriteOptions.MC_1_21_1);
        assertThat(body.getCompound("Blocks").getList("BlockEntities").size()).isEqualTo(1);
        assertThat(body.getList("Entities").size()).isEqualTo(1);
        assertThat(body.getCompound("Metadata").getString("Author")).isEqualTo("Alex");

        SchematicFile back = Schematics.read(file);
        assertThat(back.name()).isEqualTo("Gatehouse");
        Structure expected = world.copy();
        expected.normalizeToOrigin();
        Structure read = back.merged();
        assertThat(read.contentEquals(expected)).isTrue();
        assertThat(read.blockEntity(new BlockPos(1, 1, 1)).getList("Items").size()).isEqualTo(1);
        assertThat(read.entities().getFirst().x()).isEqualTo(2.5);

        // Listed as a preferred file and loads like any other.
        assertThat(lib.list()).extracting(SchematicLibrary.Entry::name).containsExactly("Gatehouse.schem");
        assertThat(lib.load(name).blockCount()).isEqualTo(world.blockCount());

        assertThatThrownBy(() -> lib.save("Gatehouse", world, WriteOptions.MC_1_21_1, "Alex", false)).hasMessageContaining("already exists");
        world.set(1000, 72, -500, BlockState.of("minecraft:glass"));
        lib.save("gatehouse.schem", world, WriteOptions.MC_1_21_1, "Alex", true);
        assertThatThrownBy(() -> lib.save("x", new Structure(), WriteOptions.MC_1_21_1, "", false)).hasMessageContaining("no blocks");
    }
}
