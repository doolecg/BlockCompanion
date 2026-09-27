package io.blockcompanion.core.autobuild;

import io.blockcompanion.core.chests.LinkedChests;
import io.blockcompanion.core.model.BlockState;

import java.util.List;
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

    /** What a block in the way is, for the replace modes ({@link AutoBuildOptions.Replace}). */
    enum Removal {
        /** A solid block with nothing in it (stone, dirt, planks, glass): {@code SOLID} and up may break it. */
        SOLID,
        /** Anything else that can be broken: plants, torches, rails, and blocks holding items or text (chests, signs). */
        OTHER,
        /** Never broken: unbreakable blocks (bedrock, barriers, portals, command blocks), or nothing known there. */
        NEVER
    }

    /** Where the drops of a block AutoBuild breaks go: the owner's linked chests in order, then the ground at the block. */
    interface Drops {
        /** The containers to put drops into, first first. */
        List<LinkedChests.Pos> chests();

        /** Items went into this chest (what is known of its contents is out of date). */
        void filled(LinkedChests.Pos chest);
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

    /** What the block there is, for deciding whether a replace mode may break it. Only asked for loaded positions. */
    default Removal removal(String dimension, int x, int y, int z) {
        return Removal.NEVER;
    }

    /**
     * Breaks the block there and sets exactly {@code state} in its place (air to clear it), with neighbour updates.
     * With {@code drops}, what the broken block drops goes into those chests, and what doesn't fit drops at the block;
     * without (creative), nothing drops. Returns false if nothing was changed.
     */
    default boolean replace(String dimension, int x, int y, int z, BlockState state, Drops drops) {
        return false;
    }

    /** Where the player is (x, y, z, feet) when online and in {@code dimension}; null otherwise. */
    default double[] position(UUID player, String dimension) {
        return null;
    }

    /** True when the player is online and in creative mode on the server (then AutoBuild takes no items). */
    boolean isCreative(UUID player);

    /** True when the player is online. */
    boolean isOnline(UUID player);

    /** Plays the "done" ding for the player, at the player. */
    void ding(UUID player);
}
