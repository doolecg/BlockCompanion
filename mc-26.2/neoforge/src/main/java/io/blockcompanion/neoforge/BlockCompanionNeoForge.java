package io.blockcompanion.neoforge;

import io.blockcompanion.BlockCompanion;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.screen.SettingsScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import io.blockcompanion.client.Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** NeoForge entry point (client only for now): registers the keys and forwards ticks, world submits and the HUD. */
@Mod(value = BlockCompanion.MOD_ID, dist = Dist.CLIENT)
public final class BlockCompanionNeoForge {
    public BlockCompanionNeoForge(IEventBus modBus, ModContainer container) {
        BlockCompanionClient.setPlatform("neoforge", container.getModInfo().getVersion().toString(),
                container.getModInfo().getOwningFile().getFile().getFilePath());
        container.registerExtensionPoint(IConfigScreenFactory.class, (mc, parent) -> new SettingsScreen(parent));
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            Keys.create(KeyMapping.Category::new);
            e.registerCategory(Keys.CATEGORY);
            Keys.ALL.forEach(e::register);
        });
        modBus.addListener(FMLClientSetupEvent.class, e -> e.enqueueWork(BlockCompanionClient::init));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> BlockCompanionClient.onClientTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener(SubmitCustomGeometryEvent.class, e ->
                BlockCompanionClient.onSubmitWorld(e.getLevelRenderState(), e.getSubmitNodeCollector(), e.getPoseStack()));
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, e -> BlockCompanionClient.onRenderHud(e.getGuiGraphics()));
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, e -> BlockCompanionClient.onClientStopping());
    }
}
