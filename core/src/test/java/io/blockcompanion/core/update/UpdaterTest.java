package io.blockcompanion.core.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UpdaterTest {
    @Test
    void comparesVersionsPartByPart() {
        assertThat(Updater.compareVersions("1.2.10", "1.2.9")).isPositive();
        assertThat(Updater.compareVersions("1.2", "1.2.0")).isZero();
        assertThat(Updater.compareVersions("v1.3.0", "1.3.0")).isZero();
        assertThat(Updater.compareVersions("0.9.0", "1.0.0")).isNegative();
        assertThat(Updater.compareVersions("1.0.0-beta.2", "1.0.0")).isNegative();
        assertThat(Updater.compareVersions("1.0.0-beta.10", "1.0.0-beta.2")).isPositive();
        assertThat(Updater.compareVersions("1.0.0-beta", "1.0.0-alpha")).isPositive();
        assertThat(Updater.compareVersions("1.0.0+build.5", "1.0.0")).isZero();
    }

    @Test
    void readsARelease() throws IOException {
        Updater.Release r = Updater.parse("""
                {"tag_name":"1.2.0","html_url":"https://github.com/doolecg/BlockCompanion/releases/tag/1.2.0","body":"# BlockCompanion 1.2.0",
                 "assets":[
                   {"name":"blockcompanion-fabric-1.21.1-1.2.0-sources.jar","browser_download_url":"https://x/s","size":10},
                   {"name":"blockcompanion-fabric-1.21.10-1.2.0.jar","browser_download_url":"https://x/b","size":20},
                   {"name":"blockcompanion-fabric-1.21.1-1.2.0.jar","browser_download_url":"https://x/a","size":30},
                   {"name":"blockcompanion-neoforge-26.3-1.2.0.jar","browser_download_url":"https://x/c","size":40},
                   {"name":"blockcompanion-paper-1.2.0.jar","browser_download_url":"https://x/p","size":50}]}
                """);
        assertThat(r.version()).isEqualTo("1.2.0");
        assertThat(r.page()).endsWith("/1.2.0");
        assertThat(r.assets()).hasSize(5);
        assertThat(Updater.pick(r, "fabric", "1.21.1").url()).isEqualTo("https://x/a");
        assertThat(Updater.pick(r, "neoforge", "26.3").size()).isEqualTo(40);
        assertThat(Updater.pick(r, "fabric", "26.2")).isNull();
    }

    @Test
    void developmentRunsNeverInstall(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Updater u = new Updater("a/b", "fabric", "1.21.1", "1.0.0", dir, m -> {
        });
        assertThat(u.canInstall()).isFalse();
        assertThat(u.state()).isEqualTo(Updater.State.IDLE);
        assertThat(u.due(1000, 10)).isTrue();
    }

    @Test
    void tidiesUpAndResumesAFinishedDownload(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path jar = Files.writeString(dir.resolve("blockcompanion-fabric-1.21.1-1.0.0.jar"), "x");
        Path old = Files.writeString(dir.resolve("blockcompanion-fabric-1.21.1-0.9.0.jar.old"), "x");
        Path part = Files.writeString(dir.resolve("blockcompanion-fabric-1.21.1-1.1.0.jar.download.part"), "x");
        Files.writeString(dir.resolve("blockcompanion-fabric-1.21.1-1.1.0.jar.download"), "x");
        Path other = Files.writeString(dir.resolve("othermod.jar.old"), "x");
        Updater u = new Updater("a/b", "fabric", "1.21.1", "1.0.0", jar, m -> {
        });
        assertThat(old).doesNotExist();
        assertThat(part).doesNotExist();
        assertThat(other).exists();
        assertThat(u.state()).isEqualTo(Updater.State.READY);
    }

    @Test
    void scriptsSwapTheJars() {
        Path old = Path.of("mods", "blockcompanion-fabric-1.21.1-1.0.0.jar");
        String win = Updater.windowsScript(old, Path.of("mods", "new.jar.download"), Path.of("mods", "new.jar"));
        assertThat(win).contains("del /f /q \"" + old + "\"").contains("move /y");
        Path quoted = Path.of("m", "it's.jar");
        assertThat(Updater.unixScript(quoted, Path.of("n.download"), Path.of("n.jar")))
                .contains("'" + quoted.toString().replace("'", "'\\''") + "'").contains("it'\\''s.jar");
    }
}
