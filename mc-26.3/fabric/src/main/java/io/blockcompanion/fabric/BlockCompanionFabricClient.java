package io.blockcompanion.fabric;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.Keys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Fabric client entry point: registers the keys and forwards ticks, world submits and the HUD to the shared client. */
public final class BlockCompanionFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Keys.create(KeyMapping.Category::register).forEach(KeyMappingHelper::registerKeyMapping);
        var mod = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("blockcompanion");
        BlockCompanionClient.setPlatform("fabric", mod.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"),
                mod.filter(c -> c.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH)
                        .map(c -> c.getOrigin().getPaths().getFirst()).orElse(null));
        BlockCompanionClient.init();
        ClientTickEvents.END_CLIENT_TICK.register(BlockCompanionClient::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> BlockCompanionClient.onClientStopping());
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
                BlockCompanionClient.onSubmitWorld(ctx.levelState(), ctx.submitNodeCollector(), ctx.poseStack()));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("blockcompanion", "layers"),
                (graphics, tickCounter) -> BlockCompanionClient.onRenderHud(graphics));
    }
}
