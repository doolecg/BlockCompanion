package io.blockcompanion.core.sync;

/**
 * The small window {@link SyncClient} needs onto the client's own placement, so it can follow a shared placement:
 * load one where the server says it is, apply moves others make, and notice moves the player makes. The client layer
 * (the Minecraft mod) implements it over its placement; tests implement it with a plain object.
 */
public interface ClientPlacementModel {

    /**
     * What the player has loaded.
     *
     * @param libraryName the schematic's library-relative file name
     * @param version     a number that changes whenever the placement changes (moved, turned, mirrored, reloaded)
     */
    record Loaded(String libraryName, PlacementPose pose, long version) {
    }

    /** The loaded placement, or null if none (or it lives in another dimension than the player). */
    Loaded current();

    /**
     * Loads a schematic from the library and puts it at {@code pose}.
     *
     * @return null on success, else why it failed (shown to the player)
     */
    String load(String libraryName, PlacementPose pose);

    /** Moves, turns and mirrors the loaded placement to {@code pose} (someone else changed it). */
    void apply(PlacementPose pose);

    /**
     * Swaps the followed placement's schematic for another library file (a new version of it), keeping where it is.
     *
     * @return null on success, else why it failed
     */
    default String reload(String libraryName) {
        Loaded cur = current();
        if (cur == null) return "Nothing loaded to update";
        return load(libraryName, cur.pose());
    }
}
