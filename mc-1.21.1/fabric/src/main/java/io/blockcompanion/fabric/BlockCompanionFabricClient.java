package io.blockcompanion.fabric;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.Keys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Fabric client entry point: registers the keys and forwards ticks and rendering to the shared client. */
public final class BlockCompanionFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Keys.ALL.forEach(KeyBindingHelper::registerKeyBinding);
        var mod = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("blockcompanion");
        BlockCompanionClient.setPlatform("fabric", mod.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"),
                mod.filter(c -> c.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH)
                        .map(c -> c.getOrigin().getPaths().getFirst()).orElse(null));
        BlockCompanionClient.init();
        ClientTickEvents.END_CLIENT_TICK.register(BlockCompanionClient::onClientTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx ->
                BlockCompanionClient.onRenderWorld(ctx.positionMatrix(), ctx.projectionMatrix(), ctx.camera(), ctx.frustum(),
                        ctx.tickCounter().getGameTimeDeltaPartialTick(false)));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> BlockCompanionClient.onClientStopping());
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> BlockCompanionClient.onRenderHud(graphics));
    }
}
