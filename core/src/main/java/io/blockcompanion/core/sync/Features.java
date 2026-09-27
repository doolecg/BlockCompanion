package io.blockcompanion.core.sync;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the server allows, sent to each player right after the hello (and again when it changes). On the wire it is a
 * list of named numbers, so a newer server can add entries that older clients simply skip; a key a client does not find
 * reads as its default.
 *
 * @param syncEnabled         the shared space is switched on
 * @param maxFileSize         largest upload in bytes
 * @param playerQuota         bytes this player may keep uploaded (-1: unlimited, e.g. admins)
 * @param playerUsed          bytes this player has uploaded so far
 * @param chunkSize           bytes of file data per transfer chunk
 * @param permissions         what this player may do ({@link Permission#mask})
 * @param autoPlaceAllowed    milestone 3: the client-side printer may run on this server
 * @param autoPlaceRange      milestone 3: how far from the player it may place, in blocks
 * @param autoPlaceRate       milestone 3: most blocks it may place per second
 * @param creativeFillAllowed milestone 3: creative fill is allowed
 * @param chestBuildAllowed   milestone 3: building from linked chests is allowed
 * @param easyPlaceAllowed    easy place (one right-click places the ghost's exact block) may be used; a server that
 *                            doesn't send the key allows it
 */
public record Features(boolean syncEnabled, long maxFileSize, long playerQuota, long playerUsed, int chunkSize, int permissions,
                       boolean autoPlaceAllowed, int autoPlaceRange, int autoPlaceRate, boolean creativeFillAllowed,
                       boolean chestBuildAllowed, boolean easyPlaceAllowed) {

    /** What a client assumes before (or without) hearing from a BlockCompanion server: nothing shared, no auto-place. */
    public static final Features NONE = new Features(false, 0, 0, 0, Protocol.CHUNK_SIZE, 0, false, 0, 0, false, false, true);

    public boolean can(Permission p) {
        return (permissions & p.bit()) != 0;
    }

    public Set<Permission> permissionSet() {
        return Permission.fromMask(permissions);
    }

    Map<String, Long> toMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("sync", syncEnabled ? 1L : 0L);
        m.put("max_file_size", maxFileSize);
        m.put("player_quota", playerQuota);
        m.put("player_used", playerUsed);
        m.put("chunk_size", (long) chunkSize);
        m.put("permissions", (long) permissions);
        m.put("auto_place", autoPlaceAllowed ? 1L : 0L);
        m.put("auto_place_range", (long) autoPlaceRange);
        m.put("auto_place_rate", (long) autoPlaceRate);
        m.put("creative_fill", creativeFillAllowed ? 1L : 0L);
        m.put("chest_build", chestBuildAllowed ? 1L : 0L);
        m.put("easy_place", easyPlaceAllowed ? 1L : 0L);
        return m;
    }

    static Features fromMap(Map<String, Long> m) {
        int chunk = (int) Math.max(1024, Math.min(Protocol.CHUNK_SIZE, m.getOrDefault("chunk_size", (long) Protocol.CHUNK_SIZE)));
        return new Features(m.getOrDefault("sync", 0L) != 0, m.getOrDefault("max_file_size", 0L),
                m.getOrDefault("player_quota", 0L), m.getOrDefault("player_used", 0L), chunk,
                m.getOrDefault("permissions", 0L).intValue(), m.getOrDefault("auto_place", 0L) != 0,
                m.getOrDefault("auto_place_range", 0L).intValue(), m.getOrDefault("auto_place_rate", 0L).intValue(),
                m.getOrDefault("creative_fill", 0L) != 0, m.getOrDefault("chest_build", 0L) != 0,
                // Unlike the other keys, a missing easy_place means allowed: servers from before it existed allowed it.
                m.getOrDefault("easy_place", 1L) != 0);
    }

    void write(Wire.Out out) {
        Map<String, Long> m = toMap();
        out.varInt(m.size());
        m.forEach((k, v) -> out.string(k).i64(v));
    }

    static Features read(Wire.In in) throws IOException {
        int n = in.count();
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(in.string(), in.i64());
        return fromMap(m);
    }
}
