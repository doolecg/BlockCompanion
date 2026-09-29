package io.blockcompanion.client;

import io.blockcompanion.client.screen.GuideScreen;
import io.blockcompanion.client.screen.LibraryScreen;
import io.blockcompanion.client.screen.SettingsScreen;
import io.blockcompanion.client.screen.TourScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.function.Supplier;

/**
 * Development check of the screens' look: with {@code BLOCKCOMPANION_UI_SELFTEST=1}, once in a world it loads a
 * schematic if none is, then opens every step of the schematic screen, every settings section and every guide page in turn and saves a
 * screenshot of each ({@code screenshots/bc-ui-*.png}), then walks the tour, a screenshot per step. {@code BLOCKCOMPANION_SELFTEST_QUIT=1} closes the game after.
 */
public final class UiSelfTest {
    public static final boolean ENABLED = "1".equals(System.getenv("BLOCKCOMPANION_UI_SELFTEST"));
    private static final int WAIT = 30;

    private record Shot(String name, Supplier<Screen> screen) {
    }

    private static final List<Shot> SHOTS = java.util.stream.Stream.concat(java.util.stream.Stream.of(
            new Shot("library-1-source", () -> new LibraryScreen(null, LibraryScreen.Step.SOURCE, false)),
            new Shot("library-1-server", () -> new LibraryScreen(null, LibraryScreen.Step.SOURCE, true)),
            new Shot("library-2-placement", () -> new LibraryScreen(null, LibraryScreen.Step.PLACEMENT, false)),
            new Shot("library-3-resources", () -> new LibraryScreen(null, LibraryScreen.Step.RESOURCES, false)),
            new Shot("library-4-blockdesigner", () -> new LibraryScreen(null, LibraryScreen.Step.LINK, false)),
            new Shot("settings-ghosts", () -> new SettingsScreen(null, SettingsScreen.Tab.GHOSTS)),
            new Shot("settings-building", () -> new SettingsScreen(null, SettingsScreen.Tab.BUILDING)),
            new Shot("settings-colours", () -> new SettingsScreen(null, SettingsScreen.Tab.COLORS)),
            new Shot("settings-keys", () -> new SettingsScreen(null, SettingsScreen.Tab.KEYS)),
            new Shot("settings-blockdesigner", () -> new SettingsScreen(null, SettingsScreen.Tab.LINK)),
            new Shot("settings-updates", () -> new SettingsScreen(null, SettingsScreen.Tab.UPDATES))),
            java.util.stream.IntStream.range(0, GuideScreen.pages()).mapToObj(i -> new Shot("guide-" + (i + 1), () -> new GuideScreen(null, i)))).toList();

    private static int ticks, index = -1, tourAt;
    private static TourScreen tour;
    /** Ticks per tour step: the camera move takes 30, the demo's ghosts a moment to mesh. */
    private static final int TOUR_WAIT = 60;

    private UiSelfTest() {
    }

    /** Every client tick while in a world. */
    static void tick(Minecraft mc) {
        if (!ENABLED || index >= SHOTS.size() + 1) return;
        ticks++;
        if (tour != null) {
            tourStep(mc);
            return;
        }
        if (ticks == 40 && BlockCompanionClient.placements().isEmpty()) {
            try {
                var files = BlockCompanionClient.library().list();
                if (!files.isEmpty()) BlockCompanionClient.load(files.get(0).name());
            } catch (java.io.IOException e) {
                BlockCompanionClient.LOG.warn("UI self-test: {}", e.toString());
            }
        }
        if (ticks < 100 || (ticks - 100) % WAIT != 0) return;
        if (index >= 0 && index < SHOTS.size()) {
            String name = "bc-ui-" + SHOTS.get(index).name() + "-" + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight() + ".png";
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                    msg -> BlockCompanionClient.LOG.info("UI self-test: {}", msg.getString()));
        }
        index++;
        if (index < SHOTS.size()) {
            mc.setScreen(SHOTS.get(index).screen().get());
        } else {
            tour = new TourScreen();
            mc.setScreen(tour);
            tourAt = 0;
        }
    }

    private static void tourStep(Minecraft mc) {
        if (++tourAt % TOUR_WAIT != 0) return;
        String name = "bc-ui-tour-" + (tourAt / TOUR_WAIT) + "-" + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight() + ".png";
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                msg -> BlockCompanionClient.LOG.info("UI self-test: {}", msg.getString()));
        if (tour.next()) return;
        tour = null;
        index = SHOTS.size() + 1;
        mc.setScreen(null);
        BlockCompanionClient.LOG.info("UI self-test done");
        if ("1".equals(System.getenv("BLOCKCOMPANION_SELFTEST_QUIT"))) mc.stop();
    }
}
