package io.blockcompanion.core.sync;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Server settings for the shared space, stored as a properties file ({@code config/blockcompanion-server.properties} on
 * Fabric/NeoForge, {@code plugins/BlockCompanion/config.properties} on Paper). Missing keys take their defaults and are
 * written back, so the file always lists every setting.
 */
public final class SyncConfig {
    /** Who has a permission on a mod server (Paper uses permission nodes instead). */
    public enum Access {
        EVERYONE, OP, NOBODY
    }

    public boolean enabled = true;
    public long maxFileSize = 8L * 1024 * 1024;
    public long playerQuota = 64L * 1024 * 1024;
    public long totalQuota = 1024L * 1024 * 1024;
    public int maxPlacementsPerPlayer = 32;
    /** An editing lock lapses this long after its holder's last move. */
    public int editLockSeconds = 10;
    /** An unfinished upload is forgotten after this long without a chunk. */
    public int uploadTimeoutSeconds = 600;
    /** Download chunks sent to each player per server tick (16 KiB each). */
    public int chunksPerTick = 4;
    public boolean allowAutoPlace = true;
    public int autoPlaceRange = 5;
    public int autoPlaceRate = 20;
    public boolean allowCreativeFill = true;
    public boolean allowChestBuild = true;
    /** Easy place: a right-click on a ghost places exactly its block (normal placement packets, vanilla rules). */
    public boolean allowEasyPlace = true;
    public final Map<Permission, Access> access = new EnumMap<>(Permission.class);

    public SyncConfig() {
        for (Permission p : Permission.values()) access.put(p, p == Permission.ADMIN ? Access.OP : Access.EVERYONE);
    }

    /** Whether a player has {@code p} on a mod server, given whether they are an operator. */
    public boolean allows(Permission p, boolean op) {
        return switch (access.get(p)) {
            case EVERYONE -> true;
            case OP -> op;
            case NOBODY -> false;
        };
    }

    public static SyncConfig load(Path file) {
        SyncConfig c = new SyncConfig();
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                // Defaults then; the file is rewritten below.
            }
        }
        c.read(p);
        try {
            c.save(file);
        } catch (IOException e) {
            // A read-only config folder still runs with what was read.
        }
        return c;
    }

    void read(Properties p) {
        enabled = bool(p, "enabled", enabled);
        maxFileSize = kb(p, "maxFileSizeKb", maxFileSize);
        playerQuota = kb(p, "playerQuotaKb", playerQuota);
        totalQuota = kb(p, "totalQuotaKb", totalQuota);
        maxPlacementsPerPlayer = (int) num(p, "maxPlacementsPerPlayer", maxPlacementsPerPlayer);
        editLockSeconds = (int) Math.max(1, num(p, "editLockSeconds", editLockSeconds));
        uploadTimeoutSeconds = (int) Math.max(10, num(p, "uploadTimeoutSeconds", uploadTimeoutSeconds));
        chunksPerTick = (int) Math.max(1, Math.min(64, num(p, "chunksPerTick", chunksPerTick)));
        allowAutoPlace = bool(p, "allowAutoPlace", allowAutoPlace);
        autoPlaceRange = (int) num(p, "autoPlaceRange", autoPlaceRange);
        autoPlaceRate = (int) num(p, "autoPlaceBlocksPerSecond", autoPlaceRate);
        allowCreativeFill = bool(p, "allowCreativeFill", allowCreativeFill);
        allowChestBuild = bool(p, "allowChestBuild", allowChestBuild);
        allowEasyPlace = bool(p, "allowEasyPlace", allowEasyPlace);
        for (Permission perm : Permission.values()) {
            String key = "permission." + perm.name().toLowerCase(Locale.ROOT);
            String v = p.getProperty(key);
            if (v == null) continue;
            try {
                access.put(perm, Access.valueOf(v.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // Keep the default.
            }
        }
    }

    Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("maxFileSizeKb", Long.toString(maxFileSize / 1024));
        p.setProperty("playerQuotaKb", Long.toString(playerQuota / 1024));
        p.setProperty("totalQuotaKb", Long.toString(totalQuota / 1024));
        p.setProperty("maxPlacementsPerPlayer", Integer.toString(maxPlacementsPerPlayer));
        p.setProperty("editLockSeconds", Integer.toString(editLockSeconds));
        p.setProperty("uploadTimeoutSeconds", Integer.toString(uploadTimeoutSeconds));
        p.setProperty("chunksPerTick", Integer.toString(chunksPerTick));
        p.setProperty("allowAutoPlace", Boolean.toString(allowAutoPlace));
        p.setProperty("autoPlaceRange", Integer.toString(autoPlaceRange));
        p.setProperty("autoPlaceBlocksPerSecond", Integer.toString(autoPlaceRate));
        p.setProperty("allowCreativeFill", Boolean.toString(allowCreativeFill));
        p.setProperty("allowChestBuild", Boolean.toString(allowChestBuild));
        p.setProperty("allowEasyPlace", Boolean.toString(allowEasyPlace));
        for (Permission perm : Permission.values()) {
            p.setProperty("permission." + perm.name().toLowerCase(Locale.ROOT), access.get(perm).name().toLowerCase(Locale.ROOT));
        }
        return p;
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            toProperties().store(w, """
                     BlockCompanion shared space (server sync).
                     Sizes are in KiB. permission.* (everyone, op or nobody) applies to Fabric/NeoForge servers;
                     on Paper/Spigot/Bukkit use the permission nodes blockcompanion.use/upload/place/lock/admin instead.
                     allowAutoPlace, autoPlace*, allowCreativeFill, allowChestBuild and allowEasyPlace are announced to
                     clients for the building helpers.""");
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static long num(Properties p, String k, long def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long kb(Properties p, String k, long defBytes) {
        return Math.max(0, num(p, k, defBytes / 1024)) * 1024;
    }
}
