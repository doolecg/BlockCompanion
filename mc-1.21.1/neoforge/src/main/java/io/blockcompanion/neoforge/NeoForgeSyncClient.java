package io.blockcompanion.neoforge;

import io.blockcompanion.network.ClientSync;
import io.blockcompanion.network.SyncKeys;
import io.blockcompanion.network.SyncNetwork;
import io.blockcompanion.network.SyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Milestone 2 (server sync), client side on NeoForge (physical client only): the key, the tick and incoming payloads. */
final class NeoForgeSyncClient {
    private NeoForgeSyncClient() {
    }

    static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(SyncKeys.SHARED));
        SyncNetwork.canSendToServer = () -> {
            ClientPacketListener conn = Minecraft.getInstance().getConnection();
            return conn != null && conn.hasChannel(SyncPayload.TYPE);
        };
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ClientSync.tick(Minecraft.getInstance()));
    }

    static void receive(byte[] data) {
        ClientSync.receive(data);
    }
}
