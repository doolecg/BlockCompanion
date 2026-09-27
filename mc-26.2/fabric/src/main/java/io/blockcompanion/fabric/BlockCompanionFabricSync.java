package io.blockcompanion.fabric;

import io.blockcompanion.network.SyncPayload;
import io.blockcompanion.server.ModSyncServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Milestone 2 (server sync), both sides: registers the {@code blockcompanion:main} payload and runs the shared space on
 * the server (dedicated, or integrated when opened to LAN). Client-only parts live in {@link BlockCompanionFabricSyncClient}.
 */
public final class BlockCompanionFabricSync implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> ModSyncServer.receive(context.player(), payload.data()));

        String version = FabricLoader.getInstance().getModContainer("blockcompanion")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ModSyncServer.start(server, "BlockCompanion-Fabric " + version, "fabric", version));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ModSyncServer.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> ModSyncServer.tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ModSyncServer.leave(handler.getPlayer().getUUID()));
    }
}
