package io.blockcompanion.core.project;

import io.blockcompanion.core.formats.SchematicFile;
import io.blockcompanion.core.formats.SchematicFormat;
import io.blockcompanion.core.formats.Schematics;
import io.blockcompanion.core.formats.WriteOptions;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.model.Box;
import io.blockcompanion.core.model.Structure;
import io.blockcompanion.core.nbt.NbtIO;
import io.blockcompanion.core.transform.Transform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@code .bdproj} the way BlockDesigner's {@code ProjectFile.save} does, for tests: format 2 with
 * {@code layers/<id>.schem} (Sponge v3), or format 1 with {@code layers/<id>.litematic}. Layer entries come first, then
 * {@code project.json}, then extra entries, as in the app.
 */
final class ProjectWriter {
    record L(String id, String name, Structure structure, BlockPos offset, Transform transform, boolean visible, String color) {
        L(String id, String name, Structure structure, BlockPos offset, Transform transform) {
            this(id, name, structure, offset, transform, true, "#7C9CFF");
        }
    }

    int format = 2;
    String name = "Test project";
    String targetVersion = "1.21.1";
    int dataVersion = WriteOptions.MC_1_21_1;
    final List<L> layers = new ArrayList<>();
    final Map<String, byte[]> extras = new LinkedHashMap<>();

    ProjectWriter add(L layer) {
        layers.add(layer);
        return this;
    }

    void write(Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            write(out);
        }
    }

    byte[] bytes() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(b);
        return b.toByteArray();
    }

    void write(OutputStream target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"format\" : ").append(format).append(",\n  \"name\" : \"").append(name).append("\",\n")
                    .append("  \"targetVersion\" : \"").append(targetVersion).append("\",\n  \"dataVersion\" : ").append(dataVersion).append(",\n");
            if (!layers.isEmpty()) json.append("  \"activeLayer\" : \"").append(layers.getFirst().id()).append("\",\n");
            json.append("  \"layers\" : [ ");
            WriteOptions opts = WriteOptions.defaults(dataVersion);
            for (int i = 0; i < layers.size(); i++) {
                L l = layers.get(i);
                String ext = format >= 2 ? ".schem" : ".litematic";
                String entry = "layers/" + l.id() + ext;
                Structure s = l.structure();
                BlockPos min = s.bounds().map(Box::min).orElse(BlockPos.ORIGIN);
                SchematicFile sf = new SchematicFile(l.name(), "", "", dataVersion, List.of(new SchematicFile.Region(l.name(), s, min)));
                SchematicFormat f = format >= 2 ? Schematics.SPONGE : Schematics.LITEMATICA;
                SchematicFormat.Encoded enc = f.write(sf, format >= 2 ? opts.withSpongeVersion(3) : opts);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIO.write(enc.root(), enc.rootName(), bytes, true);
                put(zip, entry, bytes.toByteArray());

                if (i > 0) json.append(", ");
                json.append("{\n    \"id\" : \"").append(l.id()).append("\",\n    \"name\" : \"").append(l.name()).append("\",\n")
                        .append("    \"offset\" : [ ").append(l.offset().x()).append(", ").append(l.offset().y()).append(", ").append(l.offset().z()).append(" ],\n")
                        .append("    \"rotation\" : ").append(l.transform().rotation()).append(",\n")
                        .append("    \"mirror\" : \"").append(l.transform().mirror().name()).append("\",\n")
                        .append("    \"visible\" : ").append(l.visible()).append(",\n    \"locked\" : false,\n    \"ghost\" : false,\n")
                        .append("    \"color\" : \"").append(l.color()).append("\",\n")
                        .append("    \"file\" : \"").append(entry).append("\",\n")
                        .append("    \"origin\" : [ ").append(min.x()).append(", ").append(min.y()).append(", ").append(min.z()).append(" ]\n  }");
            }
            json.append(" ]\n}");
            put(zip, "project.json", json.toString().getBytes(StandardCharsets.UTF_8));
            for (var e : extras.entrySet()) put(zip, e.getKey(), e.getValue());
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}
