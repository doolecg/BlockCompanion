package io.blockcompanion.neoforge;

import io.blockcompanion.BlockCompanion;
import io.blockcompanion.client.Keys;
import io.blockcompanion.network.ClientSync;
import io.blockcompanion.network.SyncKeys;
import io.blockcompanion.network.SyncNetwork;
import io.blockcompanion.network.SyncPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Milestone 2 (server sync), client side on NeoForge: the client payload handler, the key and the sync client's tick. */
@Mod(value = BlockCompanion.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeSyncClient {
    public NeoForgeSyncClient(IEventBus modBus) {
        modBus.addListener(RegisterClientPayloadHandlersEvent.class, e ->
                e.register(SyncPayload.TYPE, (payload, context) -> ClientSync.receive(payload.data())));
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            // The category may not exist yet if this entry point's listener runs first; creating it is idempotent,
            // and the main client entry point registers it.
            Keys.create(KeyMapping.Category::new);
            e.register(SyncKeys.create());
        });
        SyncNetwork.canSendToServer = () -> {
            ClientPacketListener conn = Minecraft.getInstance().getConnection();
            return conn != null && conn.hasChannel(SyncPayload.TYPE);
        };
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ClientSync.tick(Minecraft.getInstance()));
    }
}
