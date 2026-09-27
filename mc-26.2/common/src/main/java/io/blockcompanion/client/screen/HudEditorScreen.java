package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.hud.HudPreview;
import io.blockcompanion.core.hud.HudLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Moves and sizes the HUD pieces, like Xaero's minimap settings: drag a piece where you want it (it keeps to the nearest
 * side or the middle when the window changes size), scroll over it or use the slider to size it, switch it off, or put
 * it back where it started. Changes apply straight away and are saved when the screen closes.
 */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private final HudLayout layout;
    /** Where each piece was drawn this frame {x, y, w, h}. */
    private final Map<HudLayout.Element, int[]> boxes = new EnumMap<>(HudLayout.Element.class);
    private HudLayout.Element selected = HudLayout.Element.PANEL;
    private HudLayout.Element dragging;
    private double dragDx, dragDy;
    private int dragX, dragY;
    private ScaleSlider scale;
    private CycleButton<Boolean> shown;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD layout"));
        this.parent = parent;
        this.layout = BlockCompanionClient.config().hud;
    }

    @Override
    protected void init() {
        int y = height - 26, w = 100, gap = 4;
        int x = width / 2 - (4 * w + 3 * gap) / 2;
        scale = addRenderableWidget(new ScaleSlider(x, y, w, 20));
        shown = addRenderableWidget(CycleButton.onOffBuilder(true).create(x + w + gap, y, w, 20, Component.literal("Shown"), (b, v) -> {
            layout.set(selected, layout.get(selected).withEnabled(v));
        }));
        addRenderableWidget(Button.builder(Component.literal("Reset piece"), b -> layout.reset(selected)).bounds(x + 2 * (w + gap), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 3 * (w + gap), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset all"), b -> layout.reset()).bounds(width - 64, 4, 60, 20).build());
        select(selected);
    }

    private void select(HudLayout.Element e) {
        selected = e;
        scale.sync();
        shown.setValue(layout.get(e).enabled());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // No dimming: the pieces sit over the world as they will in play.
        g.fill(0, 0, width, 30, 0x90000000);
        g.centeredText(font, "Drag a piece to move it · scroll over it to resize · " + selected.label + " selected", width / 2, 8, 0xFFFFFFFF);
        g.centeredText(font, "Bottom left, top right, the middle...: pieces keep to the side they are on", width / 2, 18, 0xFFA0A0A0);
        // The crosshair, for the hint.
        g.fill(width / 2 - 4, height / 2, width / 2 + 5, height / 2 + 1, 0xC0FFFFFF);
        g.fill(width / 2, height / 2 - 4, width / 2 + 1, height / 2 + 5, 0xC0FFFFFF);

        LoadedPlacement lp = BlockCompanionClient.focus() != null ? BlockCompanionClient.focus()
                : BlockCompanionClient.placements().isEmpty() ? null : BlockCompanionClient.placements().get(0);
        for (HudLayout.Element e : HudLayout.Element.values()) {
            HudLayout.Placement at = layout.get(e);
            if (e == dragging) {
                // Drawn at the pointer while dragging.
                at = HudLayout.dropped(at, dragX, dragY, boxes.get(e)[2], boxes.get(e)[3], width, height);
            }
            int[] box = HudPreview.draw(g, e, at, lp);
            boxes.put(e, box);
            boolean hot = e == dragging || inside(box, mouseX, mouseY);
            int color = e == selected ? 0xFFFFE066 : hot ? 0xC0FFFFFF : 0x60FFFFFF;
            outline(g, box[0] - 1, box[1] - 1, box[2] + 2, box[3] + 2, color);
            if (!at.enabled()) g.text(font, "(off)", box[0], box[1] - 10, 0xFFFF8080, true);
            String tag = e.label + " " + Math.round(at.scale() * 100) + "%";
            if (e == selected || hot) g.text(font, tag, box[0], box[1] + box[3] + 3, color, true);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Leave the world visible.
    }

    private static boolean inside(int[] b, double x, double y) {
        return b != null && x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3];
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private HudLayout.Element at(double x, double y) {
        for (HudLayout.Element e : HudLayout.Element.values()) if (inside(boxes.get(e), x, y)) return e;
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mouseX = event.x(), mouseY = event.y();
        HudLayout.Element e = at(mouseX, mouseY);
        if (e == null || event.button() != 0) return false;
        select(e);
        dragging = e;
        int[] b = boxes.get(e);
        dragDx = mouseX - b[0];
        dragDy = mouseY - b[1];
        dragX = b[0];
        dragY = b[1];
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(event, dx, dy);
        double mouseX = event.x(), mouseY = event.y();
        int[] b = boxes.get(dragging);
        dragX = (int) Math.max(0, Math.min(width - b[2], mouseX - dragDx));
        dragY = (int) Math.max(0, Math.min(height - b[3], mouseY - dragDy));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            int[] b = boxes.get(dragging);
            layout.set(dragging, HudLayout.dropped(layout.get(dragging), dragX, dragY, b[2], b[3], width, height));
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        HudLayout.Element e = at(mouseX, mouseY);
        if (e == null) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        select(e);
        HudLayout.Placement p = layout.get(e);
        layout.set(e, p.withScale(p.scale() + (float) Math.signum(scrollY) * (shiftDown() ? 0.25f : 0.05f)));
        scale.sync();
        return true;
    }

    private static boolean shiftDown() {
        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT)
                || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT);
    }

    @Override
    public void onClose() {
        BlockCompanionClient.configChanged();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The selected piece's size, 50% to 300%. */
    private final class ScaleSlider extends AbstractSliderButton {
        ScaleSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), 0);
        }

        void sync() {
            value = (layout.get(selected).scale() - HudLayout.MIN_SCALE) / (HudLayout.MAX_SCALE - HudLayout.MIN_SCALE);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT, "Size: %d%%", Math.round(layout.get(selected).scale() * 100))));
        }

        @Override
        protected void applyValue() {
            float s = (float) (HudLayout.MIN_SCALE + value * (HudLayout.MAX_SCALE - HudLayout.MIN_SCALE));
            layout.set(selected, layout.get(selected).withScale(s));
        }
    }
}
