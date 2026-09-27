package io.blockcompanion.client.link;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.link.GameLink;
import io.blockcompanion.core.link.InstanceInfo;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.packs.repository.Pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The game client's end of the live link to BlockDesigner ({@link GameLink}): an instance file Resource Tracker finds,
 * projects it sends landing in {@code schematics/BlockDesigner/}, and a status with every loaded placement, its
 * progress and the linked chests. Client thread only.
 */
public final class ClientLink {
    /** Made on the first start and kept for the session, so stopping and starting again keeps one instance file. */
    private static GameLink link;
    private static long lastStatusKey;
    private static final Instant STARTED = Instant.now();

    /** What the link is doing, for the screens. */
    public enum State {
        /** Not listening: BlockDesigner can't find this game. */
        OFF,
        /** Listening; BlockDesigner connects within a few seconds of seeing the instance file. */
        WAITING,
        /** An app is connected. */
        CONNECTED
    }

    private ClientLink() {
    }

    /** Starts listening (and writing the instance file); does nothing when already running. */
    public static void start(Path library, String loader, String modVersion) {
        if (link == null) {
            Minecraft mc = Minecraft.getInstance();
            link = new GameLink(library, InstanceInfo.defaultFolder(), InstanceInfo.CLIENT, mc.getUser().getName(),
                    SharedConstants.getCurrentVersion().name(), loader, modVersion, mc.gameDirectory.toPath(), new Game(),
                    BlockCompanionClient.LOG::info);
        }
        link.start();
    }

    /** Stops listening: connected apps are dropped and the instance file is marked closed. */
    public static void stop() {
        if (link != null) link.close();
    }

    public static boolean running() {
        return link != null && link.running();
    }

    public static State state() {
        if (!running()) return State.OFF;
        return link.connected() ? State.CONNECTED : State.WAITING;
    }

    /** Why the link could not start, or empty. */
    public static String problem() {
        return link == null ? "" : link.problem();
    }

    /** The loopback port while running, else 0. */
    public static int port() {
        return link == null ? 0 : link.port();
    }

    /** Apps connected right now (usually "Resource Tracker"). */
    public static List<String> apps() {
        return link == null ? List.of() : link.apps();
    }

    /** The connected apps with their versions, since when, and the project each says it has open. */
    public static List<GameLink.App> connections() {
        return link == null ? List.of() : link.connections();
    }

    /** The last project BlockDesigner sent this session, or null. */
    public static GameLink.Received lastReceived() {
        return link == null ? null : link.lastReceived();
    }

    public static boolean grab() {
        return link != null && link.requestGrab();
    }

    /** Sends a placement's schematic file to BlockDesigner to edit there. False when no app is connected. */
    public static boolean edit(int slot, Path file, String name) throws IOException {
        return link != null && link.requestEdit(slot, file, name);
    }

    /** Sends the status (placements, progress, chests) to the connected apps now. False when none is connected. */
    public static boolean sendStatus() {
        if (link == null || !link.connected()) return false;
        link.sendStatusNow();
        return true;
    }

    public static void statusChanged() {
        if (link != null) link.statusChanged();
    }

    /** Every client tick. Progress changes are noticed here (a cheap sum of counters), not pushed by the trackers. */
    public static void tick() {
        if (link == null) return;
        long key = ChestTracker.get().chests().version();
        for (LoadedPlacement lp : BlockCompanionClient.placements()) {
            ProgressTracker t = lp.progress().tracker();
            key = key * 31 + (t == null ? -1 : t.version()) + lp.placement.version();
        }
        if (key != lastStatusKey) {
            lastStatusKey = key;
            link.statusChanged();
        }
        link.tick();
    }

    private static final class Game implements GameLink.Game {
        @Override
        public void projectReceived(String relative, Path file, String projectName, boolean open, String fromApp) {
            BlockCompanionClient.onProjectReceived(relative, open, fromApp);
        }

        @Override
        public void projectLinked(String relative, Path file, String projectName, int slot, String fromApp) {
            BlockCompanionClient.onProjectLinked(relative, slot, fromApp);
        }

        @Override
        public void appError(String app, String message, int slot) {
            BlockCompanionClient.onLinkError(app, message, slot);
        }

        @Override
        public InstanceInfo refresh(InstanceInfo info) {
            Minecraft mc = Minecraft.getInstance();
            String world = mc.level == null ? "" : BlockCompanionClient.worldName(mc);
            return info.withUpdate(world, clientPacks(mc), serverPacks(mc), Instant.now(), false);
        }

        @Override
        public Map<String, Object> status() {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", mc.level == null ? "" : BlockCompanionClient.worldName(mc));
            ServerData server = mc.getCurrentServer();
            m.put("server", server == null || server.ip == null ? "" : server.ip);
            m.put("dimension", mc.level == null ? "" : mc.level.dimension().identifier().toString());
            List<Object> placements = new ArrayList<>();
            LoadedPlacement active = BlockCompanionClient.active();
            for (LoadedPlacement lp : BlockCompanionClient.placements()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("slot", lp.slot);
                p.put("file", lp.name());
                p.put("name", lp.shortName());
                p.put("sha256", lp.progress().hash());
                p.put("dimension", lp.dimension);
                p.put("x", lp.placement.origin().x());
                p.put("y", lp.placement.origin().y());
                p.put("z", lp.placement.origin().z());
                p.put("rotation", lp.placement.rotation());
                p.put("mirrored", lp.placement.mirrored());
                p.put("visible", lp.visible);
                p.put("live", lp.live);
                p.put("selected", lp == active);
                p.put("locks", lp.locks.stream().map(l -> l.name().toLowerCase(Locale.ROOT)).sorted().toList());
                p.put("lockedInPlace", lp.locks.containsAll(PlacementLock.IN_PLACE));
                ProgressTracker t = lp.progress().tracker();
                if (t != null) {
                    ProgressTracker.Totals tt = t.totals();
                    p.put("total", tt.total());
                    p.put("correct", tt.correct());
                    p.put("wrong", tt.wrong());
                    p.put("missing", tt.missing());
                }
                placements.add(p);
            }
            m.put("placements", placements);
            LinkedChests chests = ChestTracker.get().chests();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("count", chests.size());
            c.put("unknown", chests.unknown());
            c.put("items", new LinkedHashMap<>(ChestTracker.get().totals()));
            m.put("chests", c);
            return m;
        }

        @Override
        public void appsChanged() {
            // The schematic list shows who is connected; it reads that every frame.
        }
    }

    /** The enabled resource packs that are files or folders, lowest priority first. */
    static List<String> clientPacks(Minecraft mc) {
        List<String> out = new ArrayList<>();
        Path dir = mc.getResourcePackDirectory();
        for (Pack pack : mc.getResourcePackRepository().getSelectedPacks()) {
            String id = pack.getId();
            if (!id.startsWith("file/")) continue;
            Path p = dir.resolve(id.substring("file/".length()));
            if (Files.exists(p)) out.add(p.toAbsolutePath().toString());
        }
        return out;
    }

    /** Packs the server sent this session: the files the game downloaded for it, oldest first. */
    static List<String> serverPacks(Minecraft mc) {
        boolean any = mc.getResourcePackRepository().getSelectedPacks().stream().anyMatch(p -> p.getId().startsWith("server"));
        if (!any) return List.of();
        Path downloads = mc.gameDirectory.toPath().resolve("downloads");
        if (!Files.isDirectory(downloads)) return List.of();
        try (Stream<Path> files = Files.walk(downloads, 3)) {
            return files.filter(Files::isRegularFile).filter(f -> {
                        try {
                            return Files.getLastModifiedTime(f).toInstant().isAfter(STARTED.minusSeconds(5)) && !f.getFileName().toString().endsWith(".json");
                        } catch (IOException e) {
                            return false;
                        }
                    }).sorted(Comparator.comparing(f -> f.toFile().lastModified())).map(f -> f.toAbsolutePath().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
