package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.autobuild.AutoBuildClient;
import io.blockcompanion.core.autobuild.AutoBuildOptions;
import io.blockcompanion.core.sync.Features;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * AutoBuild's options for this session's builds, opened from the Resources step of the B screen: speed, order, replace
 * mode, ignore air, skip missing, radius and "only build". They start from the defaults in Settings; a change applies to
 * the next build, and to the placement's running build at once. "Save as defaults" writes them to Settings.
 */
public final class AutoBuildScreen extends Screen {
    private static final int FOOTER_H = 30;

    private final Screen parent;
    private final LoadedPlacement placement;
    private OptionList list;
    private int panelX, panelY, panelW, panelH;

    public AutoBuildScreen(Screen parent, LoadedPlacement placement) {
        super(Component.literal("AutoBuild options"));
        this.parent = parent;
        this.placement = placement;
    }

    private static Features features() {
        SyncClient s = ClientSync.client();
        return s == null || !s.serverPresent() ? null : s.features();
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 2 * Ui.PAD, 460);
        panelX = (width - panelW) / 2;
        panelY = Ui.TITLE_H + 18;
        panelH = height - FOOTER_H - panelY - 2;
        list = new OptionList(minecraft, panelX + 3, panelY + 3, panelW - 6, panelH - 6);
        addRenderableWidget(list);
        AutoBuildRows.fill(list, AutoBuildClient.options(), AutoBuildClient.onlyHeld(), features(),
                (o, held) -> AutoBuildClient.setOptions(o, held, placement));

        int bw = Math.min(120, (width - 2 * Ui.PAD - 2 * Ui.GAP) / 3), by = height - FOOTER_H + 5;
        int x = width / 2 - (3 * bw + 2 * Ui.GAP) / 2;
        addRenderableWidget(Ui.button("Use defaults", "Back to the defaults in Settings (AutoBuild section).", x, by, bw, b -> {
            AutoBuildClient.useDefaults(placement);
            rebuildWidgets();
        }));
        addRenderableWidget(Ui.button("Save as defaults", "These become the defaults in Settings, for every build from now on.",
                x + bw + Ui.GAP, by, bw, b -> {
                    AutoBuildOptions o = AutoBuildClient.options();
                    BlockCompanionClient.config().setAutoBuildDefaults(o, AutoBuildClient.onlyHeld());
                    BlockCompanionClient.configChanged();
                    BlockCompanionClient.actionBar("AutoBuild defaults saved");
                }));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 2 * (bw + Ui.GAP), by, bw, 20).build());
    }

    /** The line under the title: which placement, and whether a change reaches a running build. */
    private String note() {
        String name = placement == null ? "no schematic loaded" : placement.shortName();
        if (AutoBuildClient.active(placement)) return name + ": changes apply to the running build at once";
        return name + ": for the next build" + (AutoBuildClient.customised() ? " (changed from the defaults)" : " (the defaults in Settings)");
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, panelX, panelY, panelW, panelH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ui.titleBar(g, font, title, "", width);
        Ui.centered(g, font, Ui.fit(font, note(), width - 2 * Ui.PAD), width / 2, Ui.TITLE_H + 5, Ui.MUTED);
    }

    @Override
    public void tick() {
        super.tick();
        if (list != null) list.tick();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
