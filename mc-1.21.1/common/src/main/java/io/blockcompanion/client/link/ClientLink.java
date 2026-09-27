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
    private static GameLink link;
    private static long lastStatusKey;
    private static final Instant STARTED = Instant.now();

    private ClientLink() {
    }

    public static void start(Path library, String loader, String modVersion) {
        if (link != null) return;
        Minecraft mc = Minecraft.getInstance();
        link = new GameLink(library, InstanceInfo.defaultFolder(), InstanceInfo.CLIENT, mc.getUser().getName(),
                SharedConstants.getCurrentVersion().getName(), loader, modVersion, mc.gameDirectory.toPath(), new Game(), BlockCompanionClient.LOG::info);
        link.start();
    }

    public static void stop() {
        if (link == null) return;
        link.close();
        link = null;
    }

    public static boolean running() {
        return link != null;
    }

    /** Apps connected right now (usually "Resource Tracker"). */
    public static List<String> apps() {
        return link == null ? List.of() : link.apps();
    }

    public static boolean grab() {
        return link != null && link.requestGrab();
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
            m.put("dimension", mc.level == null ? "" : mc.level.dimension().location().toString());
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
