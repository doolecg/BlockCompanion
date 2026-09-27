package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.Palette;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;

/**
 * Edits one colour of the {@link Palette}: red, green and blue sliders, a hex field and quick swatches, applied live so
 * the ghosts and boxes in the world show it straight away. Cancel puts the colour back as it was.
 */
public final class ColorScreen extends Screen {
    private final Screen parent;
    private final Palette.Entry entry;
    private final int original;
    private final ChannelSlider[] sliders = new ChannelSlider[3];
    private StringWidget preview;
    private EditBox hex;
    private boolean syncing;

    public ColorScreen(Screen parent, Palette.Entry entry) {
        super(Component.literal(entry.label));
        this.parent = parent;
        this.entry = entry;
        this.original = palette().get(entry);
    }

    private static Palette palette() {
        return BlockCompanionClient.config().colors;
    }

    @Override
    protected void init() {
        int cx = width / 2, top = Math.max(8, height / 2 - 100);
        centered(new StringWidget(title, font), top);
        centered(new StringWidget(Component.literal(entry.description).withStyle(ChatFormatting.GRAY), font), top + 13);
        preview = centered(new StringWidget(previewText(), font), top + 32);

        String[] names = {"Red", "Green", "Blue"};
        for (int i = 0; i < 3; i++) {
            sliders[i] = addRenderableWidget(new ChannelSlider(cx - 100, top + 48 + i * 24, names[i], 16 - 8 * i));
        }
        hex = addRenderableWidget(new EditBox(font, cx - 100, top + 121, 96, 18, Component.literal("Hex")));
        hex.setMaxLength(7);
        hex.setTooltip(Tooltip.create(Component.literal("Type a colour as #RRGGBB.")));
        hex.setResponder(s -> {
            if (syncing) return;
            Integer v = Palette.parseHex(s);
            if (v != null) set(v, false);
        });
        addRenderableWidget(Button.builder(Component.literal("Default"), b -> set(entry.defaultRgb, true))
                .tooltip(Tooltip.create(Component.literal("Back to " + Palette.hex(entry.defaultRgb) + ".")))
                .bounds(cx + 4, top + 120, 96, 20).build());

        int n = Palette.SWATCHES.size(), size = 20, gap = 2, sx = cx - (n * size + (n - 1) * gap) / 2;
        for (int i = 0; i < n; i++) {
            int rgb = Palette.SWATCHES.get(i);
            addRenderableWidget(Button.builder(Component.literal("█").withColor(rgb), b -> set(rgb, true))
                    .tooltip(Tooltip.create(Component.literal(Palette.hex(rgb)))).bounds(sx + i * (size + gap), top + 148, size, 20).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            set(original, true);
            onClose();
        }).bounds(cx - 100, top + 180, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(cx + 4, top + 180, 96, 20).build());
        sync(true);
    }

    private <T extends StringWidget> T centered(T w, int y) {
        w.setWidth(font.width(w.getMessage()));
        w.setPosition(width / 2 - w.getWidth() / 2, y);
        return addRenderableWidget(w);
    }

    private Component previewText() {
        int now = palette().get(entry);
        return Component.literal("Before ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("████").withColor(original))
                .append(Component.literal("   Now ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("████").withColor(now))
                .append(Component.literal(" " + Palette.hex(now)).withStyle(ChatFormatting.WHITE));
    }

    private void set(int rgb, boolean updateHex) {
        if (palette().get(entry) == rgb) {
            sync(updateHex);
            return;
        }
        palette().set(entry, rgb);
        BlockCompanionClient.configChanged();
        sync(updateHex);
    }

    /** Shows the current colour in the preview, the sliders and (unless it is being typed) the hex field. */
    private void sync(boolean updateHex) {
        int rgb = palette().get(entry);
        syncing = true;
        for (ChannelSlider s : sliders) s.show(rgb);
        if (updateHex) hex.setValue(Palette.hex(rgb));
        syncing = false;
        preview.setMessage(previewText());
        preview.setWidth(font.width(preview.getMessage()));
        preview.setX(width / 2 - preview.getWidth() / 2);
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int top = Math.max(8, height / 2 - 100);
        Ui.panel(g, width / 2 - 140, top - 6, 280, 212);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent instanceof SettingsScreen s ? s.reopen() : parent);
    }

    /** One channel of the colour, 0 to 255. */
    private final class ChannelSlider extends AbstractSliderButton {
        private final String name;
        private final int shift;

        ChannelSlider(int x, int y, String name, int shift) {
            super(x, y, 200, 20, Component.empty(), 0);
            this.name = name;
            this.shift = shift;
        }

        private int channel() {
            return (int) Math.round(value * 255);
        }

        void show(int rgb) {
            value = ((rgb >> shift) & 0xFF) / 255.0;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(name + ": " + channel()));
        }

        @Override
        protected void applyValue() {
            if (syncing) return;
            int rgb = palette().get(entry);
            rgb = (rgb & ~(0xFF << shift)) | (channel() << shift);
            set(rgb, true);
        }
    }
}
