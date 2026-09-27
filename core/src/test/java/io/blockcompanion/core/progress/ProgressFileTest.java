package io.blockcompanion.core.progress;

import io.blockcompanion.core.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressFileTest {
    static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static ProgressFile.Snapshot sample(long correct) {
        return new ProgressFile.Snapshot("Watchtower.bdproj", SHA, "Watchtower", "bctest", Instant.parse("2026-09-27T14:03:12.345Z"),
                14445, correct, 12, 14445 - correct - 12,
                Map.of("minecraft:oak_planks", new ProgressTracker.ItemProgress(640, 210),
                        "minecraft:stone", new ProgressTracker.ItemProgress(10, 0)));
    }

    @Test
    void namesAreSanitizedAndCarryTwelveHashDigits() {
        assertThat(ProgressFile.fileName("Watchtower.bdproj", SHA)).isEqualTo("Watchtower-0123456789ab.json");
        assertThat(ProgressFile.fileName("shared/My Castle (v2).schem", SHA)).isEqualTo("My_Castle__v2_-0123456789ab.json");
        assertThat(ProgressFile.sanitize("Tür ö.litematic")).isEqualTo("T_r__");
        assertThat(ProgressFile.sanitize("a.b-c_d.nbt")).isEqualTo("a.b-c_d");
        assertThat(ProgressFile.baseName("sub/Tower.v2.schem")).isEqualTo("Tower.v2");
    }

    @Test
    void jsonHasTheAgreedFields() throws IOException {
        String json = ProgressFile.toJson(sample(5120));
        Map<String, Object> o = Json.object(Json.parse(json));
        assertThat(Json.integer(o.get("format"), 0)).isEqualTo(1);
        assertThat(Json.string(o.get("schematic"), "")).isEqualTo("Watchtower.bdproj");
        assertThat(Json.string(o.get("sha256"), "")).isEqualTo(SHA);
        assertThat(Json.string(o.get("projectName"), "")).isEqualTo("Watchtower");
        assertThat(Json.string(o.get("world"), "")).isEqualTo("bctest");
        // UTC, whole seconds, trailing Z: what Instant.parse reads.
        assertThat(Json.string(o.get("updated"), "")).isEqualTo("2026-09-27T14:03:12Z");
        assertThat(Instant.parse(Json.string(o.get("updated"), ""))).isEqualTo(Instant.parse("2026-09-27T14:03:12Z"));
        assertThat(Json.integer(o.get("total"), 0)).isEqualTo(14445);
        assertThat(Json.integer(o.get("correct"), 0)).isEqualTo(5120);
        assertThat(Json.integer(o.get("wrong"), 0)).isEqualTo(12);
        assertThat(Json.integer(o.get("missing"), 0)).isEqualTo(9313);
        Map<String, Object> items = Json.object(o.get("items"));
        assertThat(items).containsOnlyKeys("minecraft:oak_planks", "minecraft:stone");
        Map<String, Object> planks = Json.object(items.get("minecraft:oak_planks"));
        assertThat(Json.integer(planks.get("needed"), 0)).isEqualTo(640);
        assertThat(Json.integer(planks.get("placed"), 0)).isEqualTo(210);
    }

    @Test
    void stringsAreEscaped() throws IOException {
        ProgressFile.Snapshot s = new ProgressFile.Snapshot("a\"b\\c.schem", SHA, "Line\nbreak", "host:25565", Instant.EPOCH, 0, 0, 0, 0, Map.of());
        Map<String, Object> o = Json.object(Json.parse(ProgressFile.toJson(s)));
        assertThat(Json.string(o.get("schematic"), "")).isEqualTo("a\"b\\c.schem");
        assertThat(Json.string(o.get("projectName"), "")).isEqualTo("Line\nbreak");
        assertThat(Json.object(o.get("items"))).isEmpty();
    }

    @Test
    void writesAtomicallyWithANonJsonTempName(@TempDir Path dir) throws IOException {
        Path folder = dir.resolve(".blockcompanion").resolve("progress");
        Path file = ProgressFile.write(folder, sample(1));
        assertThat(file).isEqualTo(folder.resolve("Watchtower-0123456789ab.json"));
        ProgressFile.write(folder, sample(2));
        try (Stream<Path> files = Files.list(folder)) {
            // Only the finished file: the temp file (…json.tmp, which a reader of *.json skips) is gone.
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("Watchtower-0123456789ab.json");
        }
        Map<String, Object> o = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        assertThat(Json.integer(o.get("correct"), 0)).isEqualTo(2);
    }

    @Test
    void writerThrottlesChangesAndSendsAHeartbeat() {
        List<Long> writes = new ArrayList<>();
        long[] now = {0};
        ProgressFile.Writer w = new ProgressFile.Writer(s -> writes.add(now[0]));
        // First tick: write straight away.
        assertThat(w.tick(now[0] = 1000, () -> sample(0))).isTrue();
        // Changes within two seconds wait.
        w.changed();
        assertThat(w.tick(now[0] = 2000, () -> sample(1))).isFalse();
        assertThat(w.tick(now[0] = 2999, () -> sample(1))).isFalse();
        assertThat(w.tick(now[0] = 3000, () -> sample(1))).isTrue();
        // Nothing changed: no write until the heartbeat a minute later.
        assertThat(w.tick(now[0] = 30_000, () -> sample(1))).isFalse();
        assertThat(w.tick(now[0] = 63_000, () -> sample(1))).isTrue();
        // Flush writes only pending changes.
        assertThat(w.flush(now[0] = 63_500, () -> sample(1))).isFalse();
        w.changed();
        assertThat(w.flush(now[0] = 63_600, () -> sample(2))).isTrue();
        assertThat(writes).containsExactly(1000L, 3000L, 63_000L, 63_600L);
    }
}
