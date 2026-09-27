package io.blockcompanion.client;

import io.blockcompanion.core.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Update check against the GitHub releases, once when the game starts while {@code updates.check} is on. A found
 * update is announced once in chat and offered on the settings screen's Updates tab, which downloads it; it installs
 * when the game quits.
 */
public final class Updates {
    public static final String REPO = "doolecg/BlockCompanion";
    public static final String RELEASES = "https://github.com/" + REPO + "/releases/latest";
    static final String MINECRAFT = "26.3";

    private static Updater updater;
    private static boolean announced;

    private Updates() {
    }

    static void init(String loader, String version, Path modJar) {
        updater = new Updater(REPO, loader, MINECRAFT, version, modJar, BlockCompanionClient.LOG::warn);
        if (BlockCompanionClient.config().updateCheck) check();
    }

    /** The updater; null before the client has started. */
    public static Updater get() {
        return updater;
    }

    public static void check() {
        if (updater != null) updater.check(Updates::checked);
    }

    public static void download() {
        if (updater != null) updater.download(null);
    }

    private static void checked() {
        if (updater.state() == Updater.State.AVAILABLE && updater.canInstall() && BlockCompanionClient.config().updateAutoDownload) {
            updater.download(null);
        }
    }

    static void tick(Minecraft mc) {
        if (updater == null) return;
        Updater.State s = updater.state();
        if (announced || mc.player == null || (s != Updater.State.AVAILABLE && s != Updater.State.READY)) return;
        announced = true;
        String version = updater.latest() != null ? updater.latest().version() : "";
        String text = s == Updater.State.READY ? "BlockCompanion " + version + " is downloaded and installs when you quit the game."
                : "BlockCompanion " + version + " is out. Open its settings (Updates tab) to "
                + (updater.canInstall() ? "install it." : "get it.");
        mc.player.sendSystemMessage(Component.literal("[BlockCompanion] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }
}
