package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.core.link.GameLink;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The live link to BlockDesigner, as rows for an {@link OptionList}: whether the link is off, waiting or connected (and
 * to which app), the project BlockDesigner sent, Start / Stop, Get project, Send status, and the link's options. Shown on
 * the schematic screen's BlockDesigner step and on the settings screen's BlockDesigner tab.
 */
final class LinkPanel {
    private LinkPanel() {
    }

    static void add(OptionList list) {
        ClientConfig c = BlockCompanionClient.config();
        list.header("Live link");

        Button startStop = Ui.button("", null, b -> {
            if (ClientLink.running()) BlockCompanionClient.stopLink();
            else BlockCompanionClient.startLink();
        });
        list.status(() -> Component.literal(stateLabel()), LinkPanel::stateDescription, LinkPanel::stateColor, startStop);

        Button grab = Ui.button("Get project", "Asks BlockDesigner for the project it has open; it arrives in front of you.",
                b -> BlockCompanionClient.grab());
        list.status(() -> Component.literal(projectLabel()), LinkPanel::projectDescription, () -> 0, grab);

        Button resend = Ui.button("Send now", "Sends your placements, their progress and your linked chests to BlockDesigner now.", b -> {
            if (ClientLink.sendStatus()) BlockCompanionClient.actionBar("Sent the build status to BlockDesigner");
        });
        list.option("Build status", "Placements, progress and chests go to BlockDesigner by themselves; this sends them now.", resend);

        Runnable update = () -> {
            ClientLink.State s = ClientLink.state();
            startStop.setMessage(Component.literal(s == ClientLink.State.OFF ? "Start link" : "Stop link"));
            grab.active = s == ClientLink.State.CONNECTED;
            resend.active = s == ClientLink.State.CONNECTED;
        };
        update.run();
        list.onTick(update);

        list.header("Link options");
        list.option("Start with the game", "Turn the link on every time the game starts, so BlockDesigner can always find it.",
                Ui.toggle(c.link, v -> c.link = v, "Off: the link only runs after you press Start link."));
        list.option("Progress file", "Writes each build's progress to ~/.blockcompanion/progress for Resource Tracker, link or not.",
                Ui.toggle(c.progressFile, v -> c.progressFile = v, null));
        list.option("In BlockDesigner", "Materials panel > Game link lists this game: tick it, then press Send or Live there.");
    }

    static String stateLabel() {
        return switch (ClientLink.state()) {
            case OFF -> ClientLink.problem().isEmpty() ? "Link off" : "Link couldn't start";
            case WAITING -> "Waiting for BlockDesigner";
            case CONNECTED -> {
                List<GameLink.App> apps = ClientLink.connections();
                StringBuilder b = new StringBuilder("Connected to ");
                for (int i = 0; i < apps.size(); i++) {
                    if (i > 0) b.append(", ");
                    b.append(apps.get(i).name());
                    if (!apps.get(i).version().isEmpty()) b.append(' ').append(apps.get(i).version());
                }
                yield b.toString();
            }
        };
    }

    static String stateDescription() {
        return switch (ClientLink.state()) {
            case OFF -> ClientLink.problem().isEmpty() ? "BlockDesigner can't see this game. Start the link to let it connect."
                    : ClientLink.problem();
            case WAITING -> "Open Resource Tracker in BlockDesigner: it finds this game within a few seconds (port " + ClientLink.port() + ").";
            case CONNECTED -> {
                List<GameLink.App> apps = ClientLink.connections();
                yield apps.isEmpty() ? "" : "Connected for " + ago(apps.get(0).since()) + ", port " + ClientLink.port()
                        + (apps.get(0).live() ? "; BlockDesigner sends every change (Live)" : "");
            }
        };
    }

    static int stateColor() {
        return switch (ClientLink.state()) {
            case OFF -> ClientLink.problem().isEmpty() ? Ui.DIM : Ui.BAD;
            case WAITING -> Ui.WARN;
            case CONNECTED -> Ui.GOOD;
        };
    }

    private static String projectLabel() {
        for (GameLink.App a : ClientLink.connections()) if (!a.project().isEmpty()) return "Open in BlockDesigner: " + a.project();
        GameLink.Received r = ClientLink.lastReceived();
        if (r != null) return "Linked project: " + r.name();
        long following = following();
        if (following > 0) return "Linked project: " + firstFollowing();
        return "No project linked yet";
    }

    private static String projectDescription() {
        GameLink.Received r = ClientLink.lastReceived();
        long following = following();
        String follow = following == 0 ? "" : following == 1 ? "1 placement follows BlockDesigner" : following + " placements follow BlockDesigner";
        if (r != null) {
            return (r.open() ? "Sent " : "Updated ") + ago(r.when()) + " ago by " + r.app() + (follow.isEmpty() ? "" : "; " + follow);
        }
        if (!follow.isEmpty()) return follow;
        return "Press Get project, or Send in BlockDesigner: it lands in schematics/" + GameLink.FOLDER + ".";
    }

    private static long following() {
        return BlockCompanionClient.placements().stream().filter(lp -> lp.fromBlockDesigner() && lp.live).count();
    }

    private static String firstFollowing() {
        for (LoadedPlacement lp : BlockCompanionClient.placements()) if (lp.fromBlockDesigner() && lp.live) return lp.shortName();
        return "";
    }

    private static String ago(Instant when) {
        return GameLink.ago(Duration.between(when, Instant.now()));
    }
}
