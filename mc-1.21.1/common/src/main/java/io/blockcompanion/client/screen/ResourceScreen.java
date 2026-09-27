package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * What a build needs, full screen (key N): the {@link ResourceList} of every item with needed, placed, carried, in
 * linked chests and still to get. Counts follow the build progress, the inventory and the linked chests by themselves
 * (checked every tick, counted again only after a change); Refresh counts again now.
 * The same list is the schematic screen's Resources step.
 */
public final class ResourceScreen extends Screen {
    private final Screen parent;
    private LoadedPlacement placement;
    private ResourceList list;
    /** Only the visible layers instead of the whole schematic. */
    private boolean visibleOnly;
    private int panelX, panelY, panelW, panelH;

    public ResourceScreen(Screen parent, LoadedPlacement placement) {
        super(Component.translatable("blockcompanion.resources.title"));
        this.parent = parent;
        this.placement = placement;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 2 * Ui.PAD, 480);
        panelX = (width - panelW) / 2;
        panelY = Ui.TITLE_H + 22;
        panelH = height - 30 - panelY;
        list = new ResourceList(minecraft, panelX + 3, panelY + 14, panelW - 6, panelH - 17);
        addRenderableWidget(list);

        List<LoadedPlacement> all = BlockCompanionClient.placements();
        if (placement == null || !all.contains(placement)) placement = all.isEmpty() ? null : all.get(0);
        int n = 4, w = Math.min(110, (width - 2 * Ui.PAD - (n - 1) * Ui.GAP) / n), y = height - 25;
        int x = width / 2 - (n * w + (n - 1) * Ui.GAP) / 2;
        if (placement != null) {
            CycleButton<LoadedPlacement> which = addRenderableWidget(CycleButton.<LoadedPlacement>builder(lp -> Component.literal(lp.shortName()))
                    .withValues(all).withInitialValue(placement).displayOnlyValue()
                    .withTooltip(lp -> Tooltip.create(Component.literal("Which loaded schematic to count. Click for the next one.")))
                    .create(x, y, w, 20, Component.empty(), (b, lp) -> {
                        placement = lp;
                        recount();
                    }));
            which.active = all.size() > 1;
        }
        addRenderableWidget(CycleButton.booleanBuilder(Component.literal("Visible layers"), Component.literal("Whole build"))
                .withInitialValue(visibleOnly).displayOnlyValue()
                .create(x + (w + Ui.GAP), y, w, 20, Component.empty(), (b, v) -> {
                    visibleOnly = v;
                    recount();
                }));
        addRenderableWidget(Ui.button("Refresh", "Count your inventory and linked chests again.", x + 2 * (w + Ui.GAP), y, w, b -> recount()));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 3 * (w + Ui.GAP), y, w, 20).build());
        recount();
    }

    private void recount() {
        list.count(placement, visibleOnly);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, panelX, panelY, panelW, panelH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        String name = placement == null ? "nothing loaded" : placement.shortName();
        Ui.titleBar(g, font, Component.literal(title.getString() + ": " + name), "", width);
        Ui.centered(g, font, list.summary(), width / 2, Ui.TITLE_H + 7, Ui.MUTED);
        list.headings(g, panelY + 4);
        if (list.children().isEmpty()) {
            Ui.centered(g, font, list.total() > 0 ? "Everything is placed" : "Nothing to count", width / 2, list.getY() + 10, Ui.GOOD);
        }
    }

    @Override
    public void tick() {
        super.tick();
        list.tick();
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
