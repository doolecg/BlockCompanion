package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.hud.Colors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * The look every BlockCompanion screen shares: dark panels with a bevelled edge like the game's own, gold section
 * headings with a rule, one option per row (label left, control right), status dots, and the text colours. Also the
 * small control builders the settings and the schematic screen use, so a toggle looks and saves the same everywhere.
 * All drawing and widget-building that differs between Minecraft versions is kept in here and in {@link OptionList}.
 */
public final class Ui {
    // ---- colours ----------------------------------------------------------------------------------------------------

    public static final int TEXT = 0xFFFFFFFF;
    public static final int SOFT = 0xFFD8D8D8;
    public static final int MUTED = 0xFF9A9A9A;
    public static final int DIM = 0xFF6A6A6A;
    /** Headings, the selected tab and step, the selected placement: the gold of the game's own highlights. */
    public static final int ACCENT = 0xFFFFE066;
    /** Tags and links: the default colour of a locked box. */
    public static final int INFO = 0xFF8FC7FF;
    /** The ends and middle of the progress bars' gradient, so states read the same as the bars. */
    public static final int GOOD = Colors.gradient(1), WARN = Colors.gradient(0.62), BAD = Colors.gradient(0);

    private static final int PANEL = 0xC0101216;
    private static final int PANEL_EDGE = 0xFF000000;
    private static final int PANEL_LIGHT = 0x22FFFFFF;
    private static final int PANEL_SHADE = 0x50000000;
    private static final int RULE = 0x38FFFFFF;
    public static final int HOVER = 0x16FFFFFF;
    public static final int SELECTED = 0x28FFE066;

    // ---- spacing ----------------------------------------------------------------------------------------------------

    public static final int GAP = 4;
    public static final int PAD = 6;
    public static final int BUTTON_H = 20;
    /** Height of the title band at the top of a screen. */
    public static final int TITLE_H = 24;

    private Ui() {
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    /** A dark translucent panel with a black edge, lit along its top and left and shaded along its bottom and right. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.fill(x, y, x + w, y + 1, PANEL_EDGE);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
        g.fill(x, y, x + 1, y + h, PANEL_EDGE);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, PANEL_LIGHT);
        g.fill(x + 1, y + 2, x + 2, y + h - 1, PANEL_LIGHT);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, PANEL_SHADE);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, PANEL_SHADE);
    }

    /** The title band across the top of a screen: the title on the left, a grey note on the right. */
    public static void titleBar(GuiGraphicsExtractor g, Font font, Component title, String note, int width) {
        g.fill(0, 0, width, TITLE_H, 0x90000000);
        g.fill(0, TITLE_H - 1, width, TITLE_H, 0x60FFE066);
        g.text(font, title, PAD + 2, (TITLE_H - 8) / 2, TEXT, true);
        if (note != null && !note.isEmpty()) {
            int room = width - font.width(title) - 3 * PAD - 8;
            String n = fit(font, note, room);
            g.text(font, n, width - PAD - font.width(n), (TITLE_H - 8) / 2, MUTED, false);
        }
    }

    /** A section heading: gold text and a faint rule to the right edge. */
    public static void section(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w) {
        g.text(font, text, x, y, ACCENT, true);
        int lx = x + font.width(text) + 6;
        if (lx < x + w) g.fill(lx, y + 4, x + w, y + 5, RULE);
    }

    /** A small square status light with a dark edge. */
    public static void dot(GuiGraphicsExtractor g, int x, int y, int argb) {
        g.fill(x, y, x + 7, y + 7, 0xFF000000);
        g.fill(x + 1, y + 1, x + 6, y + 6, argb);
        g.fill(x + 1, y + 1, x + 6, y + 2, Colors.shade(argb, 1.3));
    }

    /** A thin line under the selected tab or step. */
    public static void underline(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x + 2, y, x + w - 2, y + 2, ACCENT);
    }

    public static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, false);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component s, int x, int y, int color) {
        g.text(font, s, x, y, color, false);
    }

    public static void shadowed(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, true);
    }

    public static void shadowed(GuiGraphicsExtractor g, Font font, Component s, int x, int y, int color) {
        g.text(font, s, x, y, color, true);
    }

    public static void centered(GuiGraphicsExtractor g, Font font, String s, int cx, int y, int color) {
        g.centeredText(font, s, cx, y, color);
    }

    public static void rightText(GuiGraphicsExtractor g, Font font, String s, int right, int y, int color) {
        g.text(font, s, right - font.width(s), y, color, false);
    }

    /** Word-wrapped text; returns the height used. */
    public static int wrapped(GuiGraphicsExtractor g, Font font, Component s, int x, int y, int w, int color, int maxLines) {
        List<FormattedCharSequence> lines = font.split(s, Math.max(20, w));
        int n = Math.min(lines.size(), maxLines);
        for (int i = 0; i < n; i++) g.text(font, lines.get(i), x, y + i * 10, color, false);
        return n * 10;
    }

    public static void fill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb) {
        g.fill(x0, y0, x1, y1, argb);
    }

    public static void tooltip(GuiGraphicsExtractor g, Font font, Component text, int mouseX, int mouseY) {
        g.setTooltipForNextFrame(font, font.split(text, 240), mouseX, mouseY);
    }

    /** {@code s} cut to {@code w} pixels with an ellipsis when it doesn't fit. */
    public static String fit(Font font, String s, int w) {
        if (font.width(s) <= w) return s;
        if (w <= font.width("...")) return "";
        return font.plainSubstrByWidth(s, w - font.width("...")) + "...";
    }

    // ---- controls ---------------------------------------------------------------------------------------------------

    /** Saves the config and applies it (every control below calls this after a change). */
    private static void changed() {
        BlockCompanionClient.configChanged();
    }

    public static Button button(String label, String tooltip, Button.OnPress press) {
        Button.Builder b = Button.builder(Component.literal(label), press).size(100, BUTTON_H);
        if (tooltip != null && !tooltip.isEmpty()) b.tooltip(Tooltip.create(Component.literal(tooltip)));
        return b.build();
    }

    public static Button button(String label, String tooltip, int x, int y, int w, Button.OnPress press) {
        Button b = button(label, tooltip, press);
        b.setRectangle(w, BUTTON_H, x, y);
        return b;
    }

    /** An On / Off switch for a config value, showing only its value (the label is on the row), green when on. */
    public static CycleButton<Boolean> toggle(boolean value, Consumer<Boolean> set, String tooltip) {
        return onOff(value, v -> {
            set.accept(v);
            changed();
        }, tooltip);
    }

    /** The same switch for something that isn't a config value (it doesn't save the config). */
    public static CycleButton<Boolean> onOff(boolean value, Consumer<Boolean> set, String tooltip) {
        return CycleButton.<Boolean>builder(v -> Component.literal(v ? "On" : "Off").withColor(v ? GOOD : MUTED), value)
                .withValues(List.of(true, false)).displayOnlyValue()
                .withTooltip(v -> tooltip == null ? null : Tooltip.create(Component.literal(tooltip)))
                .create(0, 0, 100, BUTTON_H, Component.empty(), (b, v) -> set.accept(v));
    }

    /** A button that steps through {@code values}, showing only the value. */
    public static <T> CycleButton<T> cycle(List<T> values, T value, Function<T, String> name, Consumer<T> set, String tooltip) {
        List<T> all = new ArrayList<>(values);
        if (!all.contains(value)) all.add(value);
        return CycleButton.<T>builder(v -> Component.literal(name.apply(v)), value).withValues(all).displayOnlyValue()
                .withTooltip(v -> tooltip == null ? null : Tooltip.create(Component.literal(tooltip)))
                .create(0, 0, 100, BUTTON_H, Component.empty(), (b, v) -> {
                    set.accept(v);
                    changed();
                });
    }

    /** A slider from {@code min} to {@code max} in {@code step}s, showing only its value. */
    public static AbstractSliderButton slider(double min, double max, double step, double value, DoubleFunction<String> text, DoubleConsumer set,
                                              String tooltip) {
        AbstractSliderButton s = new AbstractSliderButton(0, 0, 100, BUTTON_H, Component.empty(), (value - min) / (max - min)) {
            {
                updateMessage();
            }

            private double current() {
                double v = min + this.value * (max - min);
                return Math.max(min, Math.min(max, Math.round(v / step) * step));
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.apply(current())));
            }

            @Override
            protected void applyValue() {
                set.accept(current());
                changed();
            }
        };
        if (tooltip != null) s.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return s;
    }
}
