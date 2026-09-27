package io.blockcompanion.core.placement;

import io.blockcompanion.core.model.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LayersTest {
    @Test
    void defaultShowsEverything() {
        Layers l = new Layers();
        assertThat(l.showsAll()).isTrue();
        assertThat(l.mode()).isEqualTo(Layers.Mode.BUILD_UP);
        assertThat(l.isVisible(1000)).isTrue();
        assertThat(l.describe(64)).isNull();
    }

    @Test
    void upFromFullViewStartsAtTheBottomAndBuildsUp() {
        Layers l = new Layers();
        l.step(1, 5);
        assertThat(l.level()).isZero();
        assertThat(l.isVisible(0)).isTrue();
        assertThat(l.isVisible(1)).isFalse();
        l.step(1, 5);
        assertThat(l.isVisible(0)).isTrue();
        assertThat(l.isVisible(1)).isTrue();
        assertThat(l.isVisible(2)).isFalse();
        // Past the top: back to the full view.
        for (int i = 0; i < 4; i++) l.step(1, 5);
        assertThat(l.showsAll()).isTrue();
    }

    @Test
    void downFromFullViewHidesTheTopLevel() {
        Layers l = new Layers();
        l.step(-1, 5);
        assertThat(l.level()).isEqualTo(3);
        assertThat(l.isVisible(4)).isFalse();
        for (int i = 0; i < 10; i++) l.step(-1, 5);
        assertThat(l.level()).isZero();
    }

    @Test
    void singleLevelShowsOnlyThatLevel() {
        Layers l = new Layers();
        l.toggleMode(2, 5);
        assertThat(l.mode()).isEqualTo(Layers.Mode.SINGLE);
        assertThat(l.level()).isEqualTo(2);
        assertThat(l.isVisible(1)).isFalse();
        assertThat(l.isVisible(2)).isTrue();
        assertThat(l.isVisible(3)).isFalse();
        // In single mode, stepping up at the top stays at the top.
        for (int i = 0; i < 5; i++) l.step(1, 5);
        assertThat(l.level()).isEqualTo(4);
        assertThat(l.describe(70)).isEqualTo("Layer 5 (Y 70)");
        l.toggleMode(0, 5);
        assertThat(l.describe(70)).isEqualTo("Layers up to 5 (Y 70)");
    }

    @Test
    void startLevelIsClamped() {
        Layers l = new Layers();
        l.toggleMode(99, 5);
        assertThat(l.level()).isEqualTo(4);
        l.showAll();
        l.toggleMode(-3, 5);
        assertThat(l.level()).isZero();
    }

    @Test
    void savedPlacementRoundTrips(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        SavedPlacement s = new SavedPlacement("towers/pine tower.litematic", "minecraft:overworld", new BlockPos(-12, 70, 300), 3, true, 4,
                Layers.Mode.SINGLE);
        Path f = dir.resolve("sp_My World.properties");
        s.write(f);
        assertThat(SavedPlacement.read(f)).contains(s);
        assertThat(SavedPlacement.read(dir.resolve("missing.properties"))).isEmpty();
        assertThat(SavedPlacement.safeKey("mp_play.example.com:25565")).isEqualTo("mp_play.example.com_25565");
    }
}
