package io.blockcompanion.core.sync;

import java.util.UUID;

/** A connected player, as the platform (mod server or Paper plugin) presents it to {@link SyncServer}. */
public interface SyncPeer {
    UUID id();

    String name();

    /** Whether the player has this permission right now (Bukkit node, or config plus operator status). */
    boolean has(Permission permission);

    /** Sends one encoded message on {@code blockcompanion:main}. */
    void send(byte[] message);
}
