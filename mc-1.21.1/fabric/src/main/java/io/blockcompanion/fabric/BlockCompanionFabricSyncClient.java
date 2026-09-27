package io.blockcompanion.fabric;

import io.blockcompanion.network.ClientSync;
import io.blockcompanion.network.SyncNetwork;
import io.blockcompanion.network.SyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Milestone 2 (server sync), client side: the payload receiver and the sync client's tick. */
public final class BlockCompanionFabricSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SyncNetwork.canSendToServer = () -> ClientPlayNetworking.canSend(SyncPayload.TYPE);
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> ClientSync.receive(payload.data()));
        ClientTickEvents.END_CLIENT_TICK.register(ClientSync::tick);
    }
}
