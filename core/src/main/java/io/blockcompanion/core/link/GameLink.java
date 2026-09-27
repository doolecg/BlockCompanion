package io.blockcompanion.core.link;

import io.blockcompanion.core.sync.Hashes;
import io.blockcompanion.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Everything a running game (client or server) does for the live link, independent of Minecraft: runs the
 * {@link LinkServer}, keeps the instance file fresh, writes projects the app sends into a folder, and hands events to
 * the game on its own thread through {@link #tick}. The game supplies a {@link Game}.
 */
public final class GameLink implements AutoCloseable {
    /** Where projects from BlockDesigner land, relative to the folder given to the constructor. */
    public static final String FOLDER = "BlockDesigner";
    /** Status goes out at most this often while it changes. */
    private static final long STATUS_INTERVAL_MS = 1000;

    /** The game's side. Called on the game thread, from {@link #tick}. */
    public interface Game {
        /**
         * A project arrived and was written to {@code file} ({@code relative} to the folder, e.g.
         * {@code BlockDesigner/Castle.bdproj}). {@code open}: the user sent it to the game (load it if it isn't); else
         * it is a live update (reload what is loaded from it).
         */
        void projectReceived(String relative, Path file, String projectName, boolean open, String fromApp);

        /** The world and packs for the instance file: a copy of {@code info} with them filled in. */
        InstanceInfo refresh(InstanceInfo info);

        /** The status message's body ({@code type} is added): placements, chests and so on. */
        Map<String, Object> status();

        /** An app connected or went away. */
        default void appsChanged() {
        }

        /** A message the core doesn't handle (for server-specific types). */
        default void other(String app, Map<String, Object> message) {
        }
    }

    private record Event(Runnable run) {
    }

    private final Path folder;
    private final Path instances;
    private final Game game;
    private final Consumer<String> log;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private InstanceInfo info;
    private LinkServer server;
    private long lastHeartbeat, lastStatus;
    private boolean statusDirty = true;

    /**
     * @param folder where received projects go ({@link #FOLDER} inside it)
     * @param kind   {@link InstanceInfo#CLIENT} or {@link InstanceInfo#SERVER}
     */
    public GameLink(Path folder, Path instances, String kind, String name, String minecraft, String loader, String modVersion, Path gameDir,
                    Game game, Consumer<String> log) {
        this.folder = folder;
        this.instances = instances;
        this.game = game;
        this.log = log;
        Instant now = Instant.now();
        this.info = new InstanceInfo(UUID.randomUUID().toString(), kind, name, "", minecraft, loader, modVersion, ProcessHandle.current().pid(),
                0, LinkServer.newToken(), List.of(), List.of(), now, now, false, gameDir.toAbsolutePath().normalize().toString());
    }

    /** Starts listening and writes the instance file. Failure is logged; the game runs on without the link. */
    public void start() {
        try {
            server = new LinkServer(info.token(), new Handler(), log);
            int port = server.start();
            info = new InstanceInfo(info.id(), info.kind(), info.name(), info.world(), info.minecraft(), info.loader(), info.modVersion(),
                    info.pid(), port, info.token(), info.clientPacks(), info.serverPacks(), info.started(), info.updated(), false, info.gameDir());
            InstanceInfo.cleanUp(instances, Instant.now());
            heartbeat(System.currentTimeMillis());
            log.accept("Link to BlockDesigner ready on 127.0.0.1:" + port);
        } catch (IOException e) {
            log.accept("Link to BlockDesigner not available: " + e);
            server = null;
        }
    }

    public InstanceInfo info() {
        return info;
    }

    public void rename(String name) {
        info = info.withName(name);
    }

    /** Apps connected now, by name. */
    public List<String> apps() {
        if (server == null) return List.of();
        return server.connections().stream().map(LinkServer.Connection::app).toList();
    }

    public boolean connected() {
        return server != null && !server.connections().isEmpty();
    }

    /** Something in the status changed: send it soon. */
    public void statusChanged() {
        statusDirty = true;
    }

    /** Asks the connected apps for their open project. False when none is connected. */
    public boolean requestGrab() {
        if (!connected()) return false;
        server.broadcast(Map.of("type", "grab"));
        return true;
    }

    /** Sends a message of the game's own to every app. */
    public void broadcast(Map<String, Object> message) {
        if (server != null) server.broadcast(message);
    }

    /** On the game thread, every tick: runs what arrived, keeps the instance file fresh, sends the status. */
    public void tick() {
        Event e;
        while ((e = events.poll()) != null) {
            try {
                e.run.run();
            } catch (RuntimeException ex) {
                log.accept("Link: " + ex);
            }
        }
        if (server == null) return;
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat >= InstanceInfo.HEARTBEAT.toMillis()) heartbeat(now);
        if (statusDirty && now - lastStatus >= STATUS_INTERVAL_MS && connected()) {
            statusDirty = false;
            lastStatus = now;
            server.broadcast(statusMessage());
        }
    }

    private Map<String, Object> statusMessage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "status");
        m.put("instance", info.toMap(false));
        m.putAll(game.status());
        return m;
    }

    private void heartbeat(long now) {
        lastHeartbeat = now;
        InstanceInfo fresh = game.refresh(info);
        boolean changed = !fresh.world().equals(info.world()) || !fresh.clientPacks().equals(info.clientPacks())
                || !fresh.serverPacks().equals(info.serverPacks()) || !fresh.name().equals(info.name());
        info = fresh.withUpdate(fresh.world(), fresh.clientPacks(), fresh.serverPacks(), Instant.now(), false);
        if (changed) statusDirty = true;
        try {
            info.write(instances);
        } catch (IOException ex) {
            log.accept("Link: could not write the instance file: " + ex);
        }
    }

    @Override
    public void close() {
        if (server == null) return;
        server.close();
        server = null;
        try {
            info.withUpdate(info.world(), info.clientPacks(), info.serverPacks(), Instant.now(), true).write(instances);
        } catch (IOException ex) {
            // left to go stale
        }
    }

    // ---- incoming ---------------------------------------------------------------------------------------------------

    /** A file name safe on every system, keeping the extension; {@code .bdproj} when there is none. */
    static String cleanFileName(String name) {
        String n = name == null ? "" : name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isBlank()) n = "Untitled";
        String lower = n.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".bdproj") || lower.endsWith(".schem") || lower.endsWith(".litematic") || lower.endsWith(".nbt"))) n += ".bdproj";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    /** Writes a received project to {@code FOLDER/<file>}; returns the path relative to the folder. */
    String writeProject(String fileName, byte[] data) throws IOException {
        String rel = FOLDER + "/" + cleanFileName(fileName);
        Path target = folder.resolve(rel).normalize();
        if (!target.startsWith(folder.normalize())) throw new IOException("Bad file name");
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return rel;
    }

    private final class Handler implements LinkServer.Handler {
        @Override
        public Map<String, Object> welcome(LinkServer.Connection c) {
            log.accept("Link: " + c.app() + " " + c.appVersion() + " connected");
            events.add(new Event(() -> {
                statusDirty = true;
                lastStatus = 0;
                game.appsChanged();
            }));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "welcome");
            m.put("protocol", LinkServer.PROTOCOL);
            m.put("instance", info.toMap(false));
            return m;
        }

        @Override
        public void message(LinkServer.Connection c, Map<String, Object> m) {
            String type = Json.string(m.get("type"), "");
            switch (type) {
                case "project" -> project(c, m);
                case "refresh" -> events.add(new Event(() -> {
                    statusDirty = true;
                    lastStatus = 0;
                }));
                default -> events.add(new Event(() -> game.other(c.app(), m)));
            }
        }

        private void project(LinkServer.Connection c, Map<String, Object> m) {
            String file = Json.string(m.get("file"), Json.string(m.get("name"), "Untitled"));
            String name = Json.string(m.get("name"), file);
            boolean open = Json.bool(m.get("open"), false);
            byte[] data;
            try {
                data = Base64.getDecoder().decode(Json.string(m.get("data"), ""));
            } catch (IllegalArgumentException e) {
                c.send(Map.of("type", "error", "message", "The project data isn't base64"));
                return;
            }
            String sha = Json.string(m.get("sha256"), "");
            if (!sha.isEmpty() && !sha.equalsIgnoreCase(Hashes.sha256(data))) {
                c.send(Map.of("type", "error", "message", "The project arrived damaged (hash mismatch)"));
                return;
            }
            try {
                String rel = writeProject(file, data);
                Path path = folder.resolve(rel);
                events.add(new Event(() -> game.projectReceived(rel, path, name, open, c.app())));
                c.send(Map.of("type", "received", "file", rel, "open", open));
            } catch (IOException e) {
                c.send(Map.of("type", "error", "message", "Could not save the project: " + e.getMessage()));
            }
        }

        @Override
        public void closed(LinkServer.Connection c) {
            log.accept("Link: " + c.app() + " disconnected");
            events.add(new Event(game::appsChanged));
        }
    }

    /** How long ago, for the UI ("5 s", "3 min"). */
    public static String ago(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 60) return s + " s";
        if (s < 3600) return s / 60 + " min";
        return s / 3600 + " h";
    }
}
