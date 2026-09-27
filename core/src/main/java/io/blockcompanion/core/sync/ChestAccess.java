package io.blockcompanion.core.sync;

import java.util.Map;
import java.util.UUID;

/**
 * The world as the sync server needs it for linked chests. The platform (mod server or Paper) implements it; every call
 * comes from the server's main thread.
 */
public interface ChestAccess {

    /**
     * What is in the container at the position: item id to count. Null when there is no container there, or its chunk
     * isn't loaded (then the last known contents stay).
     */
    Map<String, Long> contents(String dimension, int x, int y, int z);

    /** True when a container is there, in a loaded chunk. */
    default boolean isContainer(String dimension, int x, int y, int z) {
        return contents(dimension, x, y, z) != null;
    }

    /**
     * Moves up to {@code count} of {@code item} out of the container into the online player's inventory (only as many as
     * fit). Returns how many moved.
     */
    int take(UUID player, String dimension, int x, int y, int z, String item, int count);
}
