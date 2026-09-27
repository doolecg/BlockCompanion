package io.blockcompanion.core.sync;

import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which chests each player linked, kept by the server in {@code <shared space>/chests.json} so links survive restarts.
 */
public final class ChestLinkStore {
    /** Chests one player may link. */
    public static final int MAX_PER_PLAYER = 64;

    private final Path file;
    private final Map<UUID, Set<LinkedChests.Pos>> links = new LinkedHashMap<>();

    public ChestLinkStore(Path file) {
        this.file = file;
    }

    public void load() {
        links.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            for (Map.Entry<String, Object> e : Json.object(root.get("players")).entrySet()) {
                UUID id;
                try {
                    id = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                Set<LinkedChests.Pos> set = new LinkedHashSet<>();
                for (Object o : Json.array(e.getValue())) {
                    Map<String, Object> m = Json.object(o);
                    set.add(new LinkedChests.Pos(Json.string(m.get("dimension"), "minecraft:overworld"), Json.integer(m.get("x"), 0),
                            Json.integer(m.get("y"), 0), Json.integer(m.get("z"), 0)));
                }
                if (!set.isEmpty()) links.put(id, set);
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: start empty.
        }
    }

    public void save() throws IOException {
        Map<String, Object> players = new LinkedHashMap<>();
        links.forEach((id, set) -> {
            List<Object> list = new ArrayList<>();
            for (LinkedChests.Pos p : set) list.add(Map.of("dimension", p.dimension(), "x", p.x(), "y", p.y(), "z", p.z()));
            players.put(id.toString(), list);
        });
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(Map.of("format", 1, "players", players)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public List<LinkedChests.Pos> of(UUID player) {
        Set<LinkedChests.Pos> s = links.get(player);
        return s == null ? List.of() : List.copyOf(s);
    }

    /** Returns false when the player already has {@link #MAX_PER_PLAYER}. */
    public boolean add(UUID player, LinkedChests.Pos pos) {
        Set<LinkedChests.Pos> s = links.computeIfAbsent(player, k -> new LinkedHashSet<>());
        if (s.contains(pos)) return true;
        if (s.size() >= MAX_PER_PLAYER) return false;
        s.add(pos);
        return true;
    }

    public void remove(UUID player, LinkedChests.Pos pos) {
        Set<LinkedChests.Pos> s = links.get(player);
        if (s == null) return;
        s.remove(pos);
        if (s.isEmpty()) links.remove(player);
    }
}
