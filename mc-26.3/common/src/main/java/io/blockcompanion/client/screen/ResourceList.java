package io.blockcompanion.client.screen;

import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.hud.Bars;
import io.blockcompanion.core.items.ItemCount;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a build needs, as a list: every item with how many the blocks need, how many are placed, how many you carry,
 * how many your linked chests hold, and how many are still to get (needed - placed - carried - in chests), most still to
 * get first. Each row's bar fills red to green as the item is covered. Used by the resource screen and by the
 * schematic screen's Resources step; {@link #count} fills it.
 */
public final class ResourceList extends ObjectSelectionList<ResourceList.RowEntry> {
    /** One row: an item, needed, placed correctly, carried, in linked chests. */
    public record Row(String item, long needed, long placed, long have, long chests, BlockState icon) {
        public long toGet() {
            return Math.max(0, needed - placed - have - chests);
        }

        double covered() {
            return needed == 0 ? 1 : Math.min(1, (placed + have + chests) / (double) needed);
        }
    }

    private final Minecraft mc;
    private long total, placed, toGet;
    private int cName, cNeed, cPlaced, cHave, cChests, cToGet;

    public ResourceList(Minecraft mc, int x, int y, int w, int h) {
        super(mc, w, h, y, 22);
        this.mc = mc;
        setX(x);
        columns();
    }

    private void columns() {
        int right = getRowLeft() + getRowWidth() - 4;
        // Narrow lists drop the Placed and Have columns' spacing first.
        int step = getRowWidth() < 300 ? 40 : 48;
        cToGet = right;
        cChests = right - step;
        cHave = right - 2 * step;
        cPlaced = right - 3 * step;
        cNeed = right - 4 * step;
        cName = getRowLeft() + 22;
    }

    @Override
    public int getRowWidth() {
        return width - 14;
    }

    @Override
    protected int scrollBarX() {
        return getX() + width - 7;
    }

    @Override
    protected void extractListBackground(GuiGraphicsExtractor g) {
    }

    @Override
    protected void extractListSeparators(GuiGraphicsExtractor g) {
    }

    public long total() {
        return total;
    }

    public long placed() {
        return placed;
    }

    /** Items still to get, added up over every row. */
    public long toGet() {
        return toGet;
    }

    /** Draws the column headings on the line at {@code y} (just above the list). */
    public void headings(GuiGraphicsExtractor g, int y) {
        columns();
        var font = mc.font;
        Ui.text(g, font, "Item", cName, y, Ui.MUTED);
        Ui.rightText(g, font, "Needed", cNeed, y, Ui.MUTED);
        Ui.rightText(g, font, "Placed", cPlaced, y, Ui.MUTED);
        Ui.rightText(g, font, "Have", cHave, y, Ui.MUTED);
        Ui.rightText(g, font, "Chests", cChests, y, Ui.MUTED);
        Ui.rightText(g, font, "To get", cToGet, y, Ui.MUTED);
    }

    /** Counts {@code placement} (only the visible layers when {@code visibleOnly}) against the inventory and chests. */
    public void count(LoadedPlacement placement, boolean visibleOnly) {
        List<Row> rows = new ArrayList<>();
        total = placed = toGet = 0;
        ProgressTracker tracker = placement == null ? null : placement.progress().tracker();
        if (tracker != null && mc.player != null) {
            Layers layers = placement.layers;
            int min = 0, max = tracker.height() - 1;
            if (visibleOnly && !layers.showsAll()) {
                max = layers.level();
                if (layers.mode() == Layers.Mode.SINGLE) min = layers.level();
            }
            ProgressTracker.Totals t = tracker.totals(min, max);
            total = t.total();
            placed = t.correct();
            // Icons: the block each item places, from the schematic's own states.
            Map<String, BlockState> icons = new HashMap<>();
            for (ItemCount.Need n : ItemCount.count(placement.placement.structure().stateCounts(), List.of(), false)) icons.put(n.item(), n.icon());
            Map<String, Long> inv = inventory();
            Map<String, Long> chests = ChestTracker.get().totals();
            tracker.items(min, max).forEach((item, p) -> rows.add(new Row(item, p.needed(), p.placed(), inv.getOrDefault(item, 0L),
                    chests.getOrDefault(item, 0L), icons.get(item))));
            if (!visibleOnly) {
                for (ItemCount.Need n : ItemCount.count(Map.of(), placement.placement.structure().entities(), false)) {
                    rows.add(new Row(n.item(), n.count(), 0, inv.getOrDefault(n.item(), 0L), chests.getOrDefault(n.item(), 0L), n.icon()));
                }
            }
        }
        rows.sort((a, b) -> {
            if (a.toGet() != b.toGet()) return Long.compare(b.toGet(), a.toGet());
            long la = a.needed() - a.placed(), lb = b.needed() - b.placed();
            if (la != lb) return Long.compare(lb, la);
            return a.item().compareTo(b.item());
        });
        for (Row r : rows) toGet += r.toGet();
        clearEntries();
        for (Row r : rows) addEntry(new RowEntry(r));
        setScrollAmount(0);
    }

    private Map<String, Long> inventory() {
        Map<String, Long> have = new HashMap<>();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            have.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
        }
        return have;
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    public final class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
        final Row r;
        final ItemStack stack;
        final String name;

        RowEntry(Row r) {
            this.r = r;
            Identifier id = Identifier.tryParse(r.item());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                stack = new ItemStack(item);
                name = stack.getHoverName().getString();
            } else {
                stack = ItemStack.EMPTY;
                name = Items.pretty(r.item());
            }
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = getContentY(), left = getContentX();
            var font = mc.font;
            if (hovering) Ui.fill(g, left - 2, top - 1, left + getContentWidth() + 2, top + 19, Ui.HOVER);
            if (!stack.isEmpty()) g.item(stack, left + 1, top + 1);
            int ty = top + 2;
            Ui.text(g, font, Ui.fit(font, name, cNeed - cName - 34), cName, ty, Ui.TEXT);
            boolean done = r.placed() >= r.needed();
            Ui.rightText(g, font, fmt(r.needed()), cNeed, ty, Ui.TEXT);
            Ui.rightText(g, font, fmt(r.placed()), cPlaced, ty, done ? Ui.GOOD : 0xFFB0D8FF);
            Ui.rightText(g, font, fmt(r.have()), cHave, ty, r.have() > 0 ? Ui.TEXT : Ui.DIM);
            Ui.rightText(g, font, fmt(r.chests()), cChests, ty, r.chests() > 0 ? 0xFFFFE27A : Ui.DIM);
            Ui.rightText(g, font, r.toGet() == 0 ? "✓" : fmt(r.toGet()), cToGet, ty, r.toGet() == 0 ? Ui.GOOD : 0xFFFF7070);
            // The bar under the name: how much of the item is placed, carried or in chests.
            int bx = cName, bw = cToGet - cName;
            if (done) Bars.solid(g, bx, top + 13, bw, 1, 0xFFFFD75A);
            else Bars.gradient(g, bx, top + 13, bw, r.covered());
            if (hovering) {
                String tip = name + (r.toGet() > 0 ? "\nStill to get: " + Items.stacks(r.toGet(), r.item()) : "\nCovered");
                Ui.tooltip(g, font, Component.literal(tip), mouseX, mouseY);
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(name);
        }
    }
}
