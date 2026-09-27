package io.blockcompanion.core.sync;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a player may do in the shared space. On Paper/Spigot/Bukkit each is a permission node ({@link #node()}); on
 * Fabric and NeoForge dedicated servers the server config says, per permission, whether everyone or only operators have
 * it ({@link SyncConfig}).
 */
public enum Permission {
    /** See the shared list and placements, and download schematics. */
    USE,
    /** Upload schematics (within the quota). */
    UPLOAD,
    /** Share placements, and move or delete placements that are not locked. */
    PLACE,
    /** Lock and unlock your own placements. */
    LOCK,
    /** Ignore locks, delete anything, no per-player quota. */
    ADMIN;

    /** Bukkit permission node, e.g. {@code blockcompanion.upload}. */
    public String node() {
        return "blockcompanion." + name().toLowerCase(Locale.ROOT);
    }

    public int bit() {
        return 1 << ordinal();
    }

    public static int mask(Set<Permission> perms) {
        int m = 0;
        for (Permission p : perms) m |= p.bit();
        return m;
    }

    public static Set<Permission> fromMask(int mask) {
        EnumSet<Permission> s = EnumSet.noneOf(Permission.class);
        for (Permission p : values()) if ((mask & p.bit()) != 0) s.add(p);
        return s;
    }
}
