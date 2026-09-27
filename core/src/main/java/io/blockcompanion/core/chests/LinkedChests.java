package io.blockcompanion.core.chests;

import io.blockcompanion.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The chests (and other containers) a player linked with the selection tool, and what was last seen in each. Their
 * contents count as materials in the resource list and go to Resource Tracker. Contents come from the server when it
 * runs BlockCompanion, from the integrated server in singleplayer, or else from the last time the player opened the
 * chest. A double chest is linked by one position (the game layer picks the same half every time).
 */
public final class LinkedChests {
    /** Where a linked container is. */
    public record Pos(String dimension, int x, int y, int z) {
        String key() {
            return dimension + "|" + x + "|" + y + "|" + z;
        }

        static Pos parse(String key) {
            String[] p = key.split("\\|");
            if (p.length != 4) return null;
            try {
                return new Pos(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return x + ", " + y + ", " + z;
        }
    }

    /** Where contents came from. */
    public enum Source {
        /** Never seen: open the chest (or play on a BlockCompanion server) to count it. */
        UNKNOWN,
        /** When the player last opened it. */
        OPENED,
        /** Read from the world directly (singleplayer or a BlockCompanion server): current. */
        LIVE
    }

    /** What is known about one chest. {@code items} maps item id to count. */
    public record Seen(Map<String, Long> items, long when, Source source) {
        public Seen {
            items = Map.copyOf(items);
        }

        static final Seen NONE = new Seen(Map.of(), 0, Source.UNKNOWN);

        public long total() {
            return items.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private final Map<Pos, Seen> chests = new LinkedHashMap<>();
    private long version;
    // Worked out from the chests once per version: screens and the HUD ask every frame.
    private Map<String, Long> totals;
    private int unknown;
    private long derivedVersion = -1;

    /** Links or unlinks; returns true when it is linked now. */
    public boolean toggle(Pos pos) {
        version++;
        if (chests.remove(pos) != null) return false;
        chests.put(pos, Seen.NONE);
        return true;
    }

    public void link(Pos pos) {
        if (chests.putIfAbsent(pos, Seen.NONE) == null) version++;
    }

    public boolean unlink(Pos pos) {
        boolean removed = chests.remove(pos) != null;
        if (removed) version++;
        return removed;
    }

    public boolean isLinked(Pos pos) {
        return chests.containsKey(pos);
    }

    public void clear() {
        if (!chests.isEmpty()) version++;
        chests.clear();
    }

    /** Linked chests, in the order they were linked. */
    public List<Pos> all() {
        return List.copyOf(chests.keySet());
    }

    public int size() {
        return chests.size();
    }

    /** Changes with every link, unlink and new contents. */
    public long version() {
        return version;
    }

    public Seen seen(Pos pos) {
        return chests.getOrDefault(pos, Seen.NONE);
    }

    /** New contents for a linked chest (ignored for one that isn't linked). */
    public void setContents(Pos pos, Map<String, Long> items, long when, Source source) {
        if (!chests.containsKey(pos)) return;
        Seen old = chests.get(pos);
        // Opened contents never overwrite newer live ones.
        if (source == Source.OPENED && old.source() == Source.LIVE && old.when() > when) return;
        if (old.source() == source && old.items().equals(items)) {
            // Same contents seen again: only the time moves on, which nothing derived depends on.
            if (when > old.when()) chests.put(pos, new Seen(old.items(), when, source));
            return;
        }
        chests.put(pos, new Seen(items, when, source));
        version++;
    }

    /**
     * Items taken out of a linked chest by the mod itself (AutoBuild, a restock): subtracts them from the stored contents
     * without reading the chest again. Returns false when the chest isn't linked, isn't known yet, or held fewer than
     * that (then the stored contents can't be trusted: read the chest again).
     */
    public boolean removed(Pos pos, String item, long count) {
        Seen old = chests.get(pos);
        if (old == null || old.source() == Source.UNKNOWN) return false;
        if (count <= 0) return true;
        long have = old.items().getOrDefault(item, 0L);
        Map<String, Long> items = new TreeMap<>(old.items());
        if (have > count) items.put(item, have - count);
        else items.remove(item);
        chests.put(pos, new Seen(items, old.when(), old.source()));
        version++;
        return have >= count;
    }

    /** Item totals over every linked chest with known contents; worked out again only after a change. */
    public Map<String, Long> totals() {
        derive();
        return totals;
    }

    /** Linked chests whose contents aren't known yet. */
    public int unknown() {
        derive();
        return unknown;
    }

    private void derive() {
        if (derivedVersion == version && totals != null) return;
        Map<String, Long> t = new TreeMap<>();
        int u = 0;
        for (Seen s : chests.values()) {
            s.items().forEach((k, v) -> t.merge(k, v, Long::sum));
            if (s.source() == Source.UNKNOWN) u++;
        }
        totals = Collections.unmodifiableMap(t);
        unknown = u;
        derivedVersion = version;
    }

    // ---- files -------------------------------------------------------------------------------------------------------

    /** Writes {@code {"format":1,"chests":{"<dim>|x|y|z":{"when":..,"source":..,"items":{..}}}}} atomically. */
    public void save(Path file) throws IOException {
        Map<String, Object> list = new LinkedHashMap<>();
        chests.forEach((p, s) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("when", s.when());
            e.put("source", s.source());
            e.put("items", new TreeMap<>(s.items()));
            list.put(p.key(), e);
        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", 1);
        root.put("chests", list);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Replaces everything with what the file holds (nothing when it is missing or unreadable). */
    public void load(Path file) {
        chests.clear();
        version++;
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            for (Map.Entry<String, Object> e : Json.object(root.get("chests")).entrySet()) {
                Pos p = Pos.parse(e.getKey());
                if (p == null) continue;
                Map<String, Object> v = Json.object(e.getValue());
                Map<String, Long> items = new LinkedHashMap<>();
                Json.object(v.get("items")).forEach((k, n) -> items.put(k, Json.longValue(n, 0)));
                Source src;
                try {
                    src = Source.valueOf(Json.string(v.get("source"), "UNKNOWN"));
                } catch (IllegalArgumentException ex) {
                    src = Source.UNKNOWN;
                }
                chests.put(p, new Seen(items, Json.longValue(v.get("when"), 0), src));
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: start empty.
        }
    }

    /** Adds up item stacks as (id, count) pairs into a map. */
    public static Map<String, Long> sum(List<Map.Entry<String, Integer>> stacks) {
        Map<String, Long> m = new TreeMap<>();
        for (Map.Entry<String, Integer> s : stacks) if (s.getValue() > 0) m.merge(s.getKey(), (long) s.getValue(), Long::sum);
        return m;
    }

    /** The chests of one dimension. */
    public List<Pos> in(String dimension) {
        List<Pos> out = new ArrayList<>();
        for (Pos p : chests.keySet()) if (p.dimension().equals(dimension)) out.add(p);
        return out;
    }
}
