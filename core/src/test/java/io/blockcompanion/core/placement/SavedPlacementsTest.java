package io.blockcompanion.core.placement;

import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.hud.Colors;
import io.blockcompanion.core.hud.HudLayout;
import io.blockcompanion.core.model.BlockPos;
import io.blockcompanion.core.progress.OwnPlacements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Several placements per world, locks, linked chests, the HUD layout and the small helpers around them. */
class SavedPlacementsTest {
    @TempDir
    Path dir;

    @Test
    void slotsRoundTripWithLocksVisibilityAndLive() throws Exception {
        SavedPlacements store = new SavedPlacements(dir, "sp_World");
        SavedPlacement a = new SavedPlacement("a.schem", "minecraft:overworld", new BlockPos(1, 2, 3), 1, false, -1, Layers.Mode.BUILD_UP,
                EnumSet.of(PlacementLock.POSITION, PlacementLock.MIRROR), false, true);
        SavedPlacement b = new SavedPlacement("BlockDesigner/b.bdproj", "minecraft:the_nether", new BlockPos(-5, 70, 9), 2, true, 3,
                Layers.Mode.SINGLE);
        store.write(3, b);
        store.write(1, a);
        assertThat(store.list()).containsExactly(new SavedPlacements.Slot(1, a), new SavedPlacements.Slot(3, b));
        assertThat(SavedPlacements.freeSlot(List.of(1, 3))).isEqualTo(2);
        store.delete(1);
        assertThat(store.list()).extracting(SavedPlacements.Slot::slot).containsExactly(3);
    }

    @Test
    void oldSingleFileBecomesSlotOne() throws Exception {
        SavedPlacement old = new SavedPlacement("old.litematic", "minecraft:overworld", new BlockPos(0, 64, 0), 0, false, -1, Layers.Mode.BUILD_UP);
        old.write(dir.resolve("mp_host.properties"));
        Files.writeString(dir.resolve("mp_host.progress"), "state");
        SavedPlacements store = new SavedPlacements(dir, "mp_host");
        assertThat(store.list()).singleElement().satisfies(s -> {
            assertThat(s.slot()).isEqualTo(1);
            assertThat(s.placement().file()).isEqualTo("old.litematic");
            assertThat(s.placement().visible()).isTrue();
            assertThat(s.placement().locks()).isEmpty();
        });
        assertThat(Files.readString(store.progressFile(1))).isEqualTo("state");
        assertThat(dir.resolve("mp_host.properties")).doesNotExist();
    }

    @Test
    void lockDescriptions() {
        assertThat(PlacementLock.describe(Set.of())).isEqualTo("Unlocked");
        assertThat(PlacementLock.describe(PlacementLock.IN_PLACE)).isEqualTo("Locked in place");
        assertThat(PlacementLock.describe(PlacementLock.ALL)).isEqualTo("Locked");
        assertThat(PlacementLock.describe(EnumSet.of(PlacementLock.ROTATION, PlacementLock.LAYERS))).isEqualTo("Locked: rotation, layers");
    }

    @Test
    void linkedChestsTotalsAndFile() throws Exception {
        LinkedChests c = new LinkedChests();
        LinkedChests.Pos p1 = new LinkedChests.Pos("minecraft:overworld", 1, 2, 3), p2 = new LinkedChests.Pos("minecraft:overworld", 4, 5, 6);
        assertThat(c.toggle(p1)).isTrue();
        c.link(p2);
        assertThat(c.unknown()).isEqualTo(2);
        c.setContents(p1, Map.of("minecraft:stone", 10L), 100, LinkedChests.Source.LIVE);
        c.setContents(p2, Map.of("minecraft:stone", 5L, "minecraft:dirt", 1L), 100, LinkedChests.Source.OPENED);
        assertThat(c.totals()).containsEntry("minecraft:stone", 15L).containsEntry("minecraft:dirt", 1L);
        // An older look inside doesn't replace newer live contents.
        c.setContents(p1, Map.of(), 50, LinkedChests.Source.OPENED);
        assertThat(c.seen(p1).total()).isEqualTo(10);

        Path f = dir.resolve("chests.json");
        c.save(f);
        LinkedChests back = new LinkedChests();
        back.load(f);
        assertThat(back.all()).containsExactly(p1, p2);
        assertThat(back.totals()).isEqualTo(c.totals());
        assertThat(back.seen(p2).source()).isEqualTo(LinkedChests.Source.OPENED);
        assertThat(back.toggle(p1)).isFalse();
        assertThat(back.isLinked(p1)).isFalse();
    }

    @Test
    void hudLayoutKeepsPiecesOnScreenAndSnapsToSides() {
        HudLayout l = new HudLayout();
        HudLayout.Placement panel = l.get(HudLayout.Element.PANEL);
        // Bottom left by default, 4 px in.
        assertThat(panel.topLeft(400, 300, 100, 50)).containsExactly(4, 246);
        // The hint's right edge sits 8 px left of the centre, whatever its width.
        HudLayout.Placement hint = l.get(HudLayout.Element.HINT);
        int[] tl = hint.topLeft(400, 300, 60, 8);
        assertThat(tl[0] + 60).isEqualTo(192);

        HudLayout.Placement moved = HudLayout.dropped(panel, 380, 10, 100, 50, 400, 300);
        assertThat(moved.fx()).isEqualTo(1);
        assertThat(moved.fy()).isEqualTo(0);
        assertThat(moved.topLeft(400, 300, 100, 50)).containsExactly(300, 10);
        assertThat(moved.topLeft(800, 600, 100, 50)).containsExactly(700, 10);

        l.set(HudLayout.Element.PANEL, moved.withScale(1.73f));
        Properties p = new Properties();
        l.write(p);
        HudLayout back = new HudLayout();
        back.read(p);
        assertThat(back.get(HudLayout.Element.PANEL).scale()).isEqualTo(1.75f);
        assertThat(back.get(HudLayout.Element.PANEL).topLeft(400, 300, 100, 50)).containsExactly(300, 10);
        assertThat(moved.withScale(9).scale()).isEqualTo(HudLayout.MAX_SCALE);
    }

    @Test
    void gradientGoesRedToGreen() {
        assertThat(Colors.gradient(0)).isEqualTo(0xFFD8413A);
        assertThat(Colors.gradient(1)).isEqualTo(0xFF5DBE4A);
        assertThat(Colors.gradient(-3)).isEqualTo(Colors.gradient(0));
        int mid = Colors.gradient(0.5);
        assertThat((mid >> 16) & 0xFF).isGreaterThan((mid >> 8 & 0xFF) - 60);
    }

    @Test
    void ownPlacementsAreNearRecentClicks() {
        OwnPlacements own = new OwnPlacements();
        own.clicked(10, 64, 10, 1000);
        assertThat(own.mine(10, 64, 10, 1200)).isTrue();
        assertThat(own.mine(11, 65, 10, 1200)).isTrue();
        assertThat(own.mine(12, 64, 10, 1200)).isFalse();
        assertThat(own.mine(10, 64, 10, 1000 + OwnPlacements.WINDOW_MS + 1)).isFalse();
    }
}
