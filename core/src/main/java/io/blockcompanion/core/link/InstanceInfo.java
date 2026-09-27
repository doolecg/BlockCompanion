package io.blockcompanion.core.link;

import io.blockcompanion.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a running BlockCompanion (a game client, a mod server or a Paper server) on this computer tells BlockDesigner it
 * is there: {@code ~/.blockcompanion/instances/<id>.json} with the loopback port and token of its {@link LinkServer}.
 * The file is rewritten every few seconds while the game runs ({@code updated}), marked {@code closed} when it stops,
 * and removed a week after that. See {@code docs/link-protocol.md}.
 *
 * @param id         random id for this run, also the file name
 * @param kind       {@code client} or {@code server}
 * @param name       what to show: the player's name for a client, the server's name (MOTD) for a server
 * @param world      a client's world (singleplayer folder or server address), a server's world folder
 * @param minecraft  the Minecraft version
 * @param loader     {@code fabric}, {@code neoforge} or {@code paper}
 * @param port       the loopback port of the link server
 * @param token      secret the app sends in its hello; the file is only readable by this user, which is the pairing
 * @param clientPacks a client's enabled resource packs, lowest priority first, as files or folders
 * @param serverPacks resource packs a server pushes: files the client downloaded, or the server's pack URL
 * @param gameDir    the game's (or server's) folder, where {@code mods} (or {@code plugins}) is
 */
public record InstanceInfo(String id, String kind, String name, String world, String minecraft, String loader, String modVersion,
                           long pid, int port, String token, List<String> clientPacks, List<String> serverPacks, Instant started,
                           Instant updated, boolean closed, String gameDir) {
    public static final int FORMAT = 1;
    public static final String CLIENT = "client", SERVER = "server";
    /** Rewritten this often while running. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(5);
    /** No rewrite for this long and the instance counts as gone (crashed or killed). */
    public static final Duration STALE_AFTER = Duration.ofSeconds(20);
    /** Closed files are removed this long after their last update. */
    public static final Duration FORGET_AFTER = Duration.ofDays(7);

    public InstanceInfo {
        clientPacks = List.copyOf(clientPacks);
        serverPacks = List.copyOf(serverPacks);
    }

    /** {@code <user home>/.blockcompanion/instances}. */
    public static Path defaultFolder() {
        return Path.of(System.getProperty("user.home"), ".blockcompanion", "instances");
    }

    public boolean alive(Instant now) {
        return !closed && Duration.between(updated, now).compareTo(STALE_AFTER) < 0;
    }

    public InstanceInfo withUpdate(String world, List<String> clientPacks, List<String> serverPacks, Instant now, boolean closed) {
        return new InstanceInfo(id, kind, name, world, minecraft, loader, modVersion, pid, port, token, clientPacks, serverPacks, started, now,
                closed, gameDir);
    }

    public InstanceInfo withName(String name) {
        return new InstanceInfo(id, kind, name, world, minecraft, loader, modVersion, pid, port, token, clientPacks, serverPacks, started,
                updated, closed, gameDir);
    }

    /** The public part, as sent in the link's welcome (no token). */
    public Map<String, Object> toMap(boolean withToken) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("id", id);
        m.put("kind", kind);
        m.put("name", name);
        m.put("world", world);
        m.put("minecraft", minecraft);
        m.put("loader", loader);
        m.put("modVersion", modVersion);
        m.put("pid", pid);
        m.put("port", port);
        if (withToken) m.put("token", token);
        m.put("clientPacks", clientPacks);
        m.put("serverPacks", serverPacks);
        m.put("started", started.truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("updated", updated.truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("closed", closed);
        m.put("gameDir", gameDir);
        return m;
    }

    public static InstanceInfo fromMap(Map<String, Object> m) throws IOException {
        if (Json.integer(m.get("format"), -1) != FORMAT) throw new IOException("Unknown instance file format");
        String id = Json.string(m.get("id"), "");
        if (id.isBlank()) throw new IOException("No id");
        List<String> cp = new ArrayList<>(), sp = new ArrayList<>();
        for (Object o : Json.array(m.get("clientPacks"))) cp.add(Json.string(o, ""));
        for (Object o : Json.array(m.get("serverPacks"))) sp.add(Json.string(o, ""));
        return new InstanceInfo(id, Json.string(m.get("kind"), CLIENT), Json.string(m.get("name"), ""), Json.string(m.get("world"), ""),
                Json.string(m.get("minecraft"), ""), Json.string(m.get("loader"), ""), Json.string(m.get("modVersion"), ""),
                Json.longValue(m.get("pid"), 0), Json.integer(m.get("port"), 0), Json.string(m.get("token"), ""), cp, sp,
                instant(m.get("started")), instant(m.get("updated")), Json.bool(m.get("closed"), false), Json.string(m.get("gameDir"), ""));
    }

    private static Instant instant(Object o) {
        try {
            return Instant.parse(Json.string(o, ""));
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    public Path file(Path folder) {
        return folder.resolve(id + ".json");
    }

    /** Writes the file atomically ({@code .json.tmp}, then moved over). */
    public void write(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path target = file(folder);
        Path tmp = folder.resolve(id + ".json.tmp");
        Files.writeString(tmp, Json.write(toMap(true)), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static InstanceInfo read(Path file) throws IOException {
        return fromMap(Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8))));
    }

    /** Every readable instance file in the folder; unreadable ones are skipped. */
    public static List<InstanceInfo> list(Path folder) {
        List<InstanceInfo> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path f : files) {
                try {
                    out.add(read(f));
                } catch (IOException | RuntimeException e) {
                    // partly written or not ours
                }
            }
        } catch (IOException e) {
            // folder went away
        }
        return out;
    }

    /** Removes files of instances that closed or went stale more than {@link #FORGET_AFTER} ago. */
    public static void cleanUp(Path folder, Instant now) {
        for (InstanceInfo i : list(folder)) {
            if (Duration.between(i.updated(), now).compareTo(FORGET_AFTER) > 0) {
                try {
                    Files.deleteIfExists(i.file(folder));
                } catch (IOException e) {
                    // try again next time
                }
            }
        }
    }
}
