package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.model.BlockState;

import java.util.UUID;

/**
 * The world as AutoBuild needs it. The platform (a Fabric or NeoForge server, or Paper) implements it; every call comes
 * from the server's main thread. Blocks are set directly, the way a structure is pasted: nothing is used or clicked, so
 * doors and trapdoors don't flip and no container opens.
 */
public interface BuildWorld {
    /** Why a block can't go down right now. */
    enum Check {
        /** It can be placed. */
        OK,
        /** It needs support that isn't there yet (a torch without its wall, sand over a hole): try again later. */
        UNSUPPORTED,
        /** This game has no such block (a mod that isn't installed). */
        UNKNOWN_BLOCK
    }

    /** False once the dimension is gone (unloaded, or the server is stopping). */
    boolean dimensionExists(String dimension);

    /** True when the block's chunk is loaded. */
    boolean isLoaded(String dimension, int x, int y, int z);

    /** The block there now, in core terms. Only asked for loaded positions. */
    BlockState get(String dimension, int x, int y, int z);

    /** Whether {@code state} could be placed there now. */
    Check check(String dimension, int x, int y, int z, BlockState state);

    /** Sets exactly {@code state} there, with neighbour updates. Returns false if the game refused. */
    boolean place(String dimension, int x, int y, int z, BlockState state);

    /** True when the player is online and in creative mode on the server (then AutoBuild takes no items). */
    boolean isCreative(UUID player);

    /** True when the player is online. */
    boolean isOnline(UUID player);

    /** Plays the "done" ding for the player, at the player. */
    void ding(UUID player);
}
