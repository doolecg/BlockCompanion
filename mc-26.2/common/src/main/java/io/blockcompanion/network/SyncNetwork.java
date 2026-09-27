package io.blockcompanion.network;

import java.util.function.BooleanSupplier;

/**
 * The one thing about the channel that differs per loader: whether the server we are connected to listens on
 * {@code blockcompanion:main}. The platform's client entry point sets {@link #canSendToServer}; sending itself uses
 * vanilla custom-payload packets, which both loaders route through their payload registries.
 */
public final class SyncNetwork {
    /** True when the server registered the channel (a BlockCompanion mod server or the Paper plugin). */
    public static BooleanSupplier canSendToServer = () -> false;

    private SyncNetwork() {
    }
}
