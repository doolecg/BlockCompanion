package io.blockcompanion.neoforge;

import io.blockcompanion.BlockCompanion;
import io.blockcompanion.network.SyncPayload;
import io.blockcompanion.server.ModSyncServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Milestone 2 (server sync), both sides: a second entry point of the mod (FML runs every {@code @Mod} class for the
 * mod id) that registers the {@code blockcompanion:main} payload and runs the shared space on the server. The payload
 * is optional, so the client still joins servers without BlockCompanion (vanilla, or Paper with or without the plugin).
 */
@Mod(BlockCompanion.MOD_ID)
public final class BlockCompanionNeoForgeSync {
    public BlockCompanionNeoForgeSync(IEventBus modBus, ModContainer container) {
        String software = "BlockCompanion-NeoForge " + container.getModInfo().getVersion();
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> e.registrar("1").optional()
                .playBidirectional(SyncPayload.TYPE, SyncPayload.CODEC, BlockCompanionNeoForgeSync::handle));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> ModSyncServer.start(e.getServer(), software, "neoforge", container.getModInfo().getVersion().toString()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> ModSyncServer.stop());
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> ModSyncServer.tick());
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> ModSyncServer.leave(e.getEntity().getUUID()));
        if (FMLEnvironment.dist.isClient()) NeoForgeSyncClient.init(modBus);
    }

    /** Runs on the main thread (the registrar's default). */
    private static void handle(SyncPayload payload, IPayloadContext context) {
        if (context.flow().isServerbound()) {
            if (context.player() instanceof ServerPlayer player) ModSyncServer.receive(player, payload.data());
        } else {
            NeoForgeSyncClient.receive(payload.data());
        }
    }
}
