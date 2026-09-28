package io.blockcompanion.client;

import io.blockcompanion.client.screen.GuideScreen;
import io.blockcompanion.client.screen.LibraryScreen;
import io.blockcompanion.client.screen.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.function.Supplier;

/**
 * Development check of the screens' look: with {@code BLOCKCOMPANION_UI_SELFTEST=1}, once in a world it loads a
 * schematic if none is, then opens every step of the schematic screen, every settings section and every guide page in turn and saves a
 * screenshot of each ({@code screenshots/bc-ui-*.png}). {@code BLOCKCOMPANION_SELFTEST_QUIT=1} closes the game after.
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

    private static int ticks, index = -1;

    private UiSelfTest() {
    }

    /** Every client tick while in a world. */
    static void tick(Minecraft mc) {
        if (!ENABLED || index >= SHOTS.size() + 1) return;
        ticks++;
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
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
                    msg -> BlockCompanionClient.LOG.info("UI self-test: {}", msg.getString()));
        }
        index++;
        if (index < SHOTS.size()) {
            mc.gui.setScreen(SHOTS.get(index).screen().get());
        } else {
            mc.gui.setScreen(null);
            BlockCompanionClient.LOG.info("UI self-test done");
            if ("1".equals(System.getenv("BLOCKCOMPANION_SELFTEST_QUIT"))) mc.stop();
        }
    }
}
