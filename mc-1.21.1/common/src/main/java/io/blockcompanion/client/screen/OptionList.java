package io.blockcompanion.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A scrolling list of rows in the BlockCompanion look: section headings, and option rows with the label (and a short
 * grey description under it) on the left and the control on the right. Rows can show live text and a status light,
 * updated every frame, and {@link #onTick} runs code every tick (for buttons whose label follows a state).
 */
public final class OptionList extends ContainerObjectSelectionList<OptionList.Row> {
    /** Every row is this tall: a label line, a description line and padding (a control is 20). */
    public static final int ROW = 25;

    private final Font font;
    private final List<Runnable> tickers = new ArrayList<>();

    public OptionList(Minecraft mc, int x, int y, int w, int h) {
        super(mc, w, h, y, ROW);
        this.font = mc.font;
        setX(x);
    }

    @Override
    public int getRowWidth() {
        return width - 18;
    }

    @Override
    protected int getScrollbarPosition() {
        return getX() + width - 7;
    }

    @Override
    protected void renderListBackground(GuiGraphics g) {
        // The screen draws a panel behind the list.
    }

    @Override
    protected void renderListSeparators(GuiGraphics g) {
    }

    /** How wide the control column is: two fifths of the row, between 90 and 160 pixels. */
    public int controlWidth() {
        return Math.max(90, Math.min(160, getRowWidth() * 2 / 5));
    }

    public void clear() {
        clearEntries();
        tickers.clear();
        setScrollAmount(0);
    }

    /** Runs every tick while the list is shown. */
    public void onTick(Runnable r) {
        tickers.add(r);
    }

    public void tick() {
        for (Runnable r : tickers) r.run();
    }

    // ---- adding rows ------------------------------------------------------------------------------------------------

    public void header(String text) {
        addEntry(new Header(text));
    }

    /** An option: {@code label} and {@code description} on the left, the controls (one or two, share the width) right. */
    public void option(String label, String description, AbstractWidget... controls) {
        option(Component.literal(label), description, controls);
    }

    public void option(Component label, String description, AbstractWidget... controls) {
        addEntry(new Option(() -> label, () -> description, null, controls));
    }

    /** A row with live text and a status light ({@code dot} returns 0 for none). */
    public void status(Supplier<Component> label, Supplier<String> description, IntSupplier dot, AbstractWidget... controls) {
        addEntry(new Option(label, description, dot, controls));
    }

    /** Buttons across the whole row, sharing its width. */
    public void wide(AbstractWidget... controls) {
        addEntry(new Wide(controls));
    }

    // ---- rows -------------------------------------------------------------------------------------------------------

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    private final class Header extends Row {
        private final String text;

        Header(String text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            Ui.section(g, font, text, rowLeft + 2, rowTop + ROW - 13, rowWidth - 4);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class Option extends Row {
        private final Supplier<Component> label;
        private final Supplier<String> description;
        private final IntSupplier dot;
        private final List<AbstractWidget> controls;
        /** Controls made 40 pixels wide or less (a reset button) keep their width; the others share the rest. */
        private final List<Integer> fixed = new ArrayList<>();

        Option(Supplier<Component> label, Supplier<String> description, IntSupplier dot, AbstractWidget... controls) {
            this.label = label;
            this.description = description;
            this.dot = dot;
            this.controls = new ArrayList<>();
            for (AbstractWidget w : controls) {
                if (w == null) continue;
                this.controls.add(w);
                fixed.add(w.getWidth() <= 40 ? w.getWidth() : 0);
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int x = rowLeft, y = rowTop, w = rowWidth;
            if (hovering) Ui.fill(g, x - 2, y - 1, x + w + 2, y + ROW - 3, Ui.HOVER);
            int cw = controls.isEmpty() ? 0 : controlWidth();
            int cx = x + w - cw;
            int n = controls.size();
            int fixedWidth = 0, flexible = 0;
            for (int f : fixed) {
                if (f > 0) fixedWidth += f;
                else flexible++;
            }
            int each = flexible == 0 ? 0 : (cw - fixedWidth - (n - 1) * Ui.GAP) / flexible;
            int px = cx;
            for (int i = 0; i < n; i++) {
                AbstractWidget c = controls.get(i);
                int cwi = fixed.get(i) > 0 ? fixed.get(i) : each;
                if (i == n - 1) cwi = cx + cw - px;
                c.setWidth(cwi);
                c.setX(px);
                c.setY(y + 1);
                c.render(g, mouseX, mouseY, partialTick);
                px += cwi + Ui.GAP;
            }
            int tx = x + 2;
            int d = dot == null ? 0 : dot.getAsInt();
            if (d != 0) {
                Ui.dot(g, tx, y + 3, d);
                tx += 11;
            }
            int room = (n == 0 ? x + w : cx - 6) - tx;
            Component l = label.get();
            String text = Ui.fit(font, l.getString(), room);
            Ui.shadowed(g, font, text.equals(l.getString()) ? l : Component.literal(text), tx, y + 2, Ui.TEXT);
            String desc = description == null ? null : description.get();
            if (desc != null && !desc.isEmpty()) {
                String shown = Ui.fit(font, desc, room);
                Ui.text(g, font, shown, tx, y + 13, Ui.MUTED);
                boolean overLabel = mouseX >= x && mouseX < (n == 0 ? x + w : cx - 4) && mouseY >= y && mouseY < y + ROW - 3;
                if (overLabel && (!shown.equals(desc) || !text.equals(l.getString()))) {
                    Ui.tooltip(g, font, Component.literal(l.getString() + "\n").withColor(Ui.ACCENT).append(Component.literal(desc).withColor(Ui.SOFT)),
                            mouseX, mouseY);
                }
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return controls;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return controls;
        }
    }

    private final class Wide extends Row {
        private final List<AbstractWidget> controls = new ArrayList<>();

        Wide(AbstractWidget... controls) {
            for (AbstractWidget w : controls) if (w != null) this.controls.add(w);
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int x = rowLeft, y = rowTop, w = rowWidth;
            int n = Math.max(1, controls.size());
            int each = (w - (n - 1) * Ui.GAP) / n;
            for (int i = 0; i < controls.size(); i++) {
                AbstractWidget c = controls.get(i);
                c.setWidth(i == n - 1 ? w - (n - 1) * (each + Ui.GAP) : each);
                c.setX(x + i * (each + Ui.GAP));
                c.setY(y + 1);
                c.render(g, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return controls;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return controls;
        }
    }
}
