package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.hud.Bars;
import io.blockcompanion.core.items.ItemCount;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.model.BlockState;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * schematic screen's Resources step; {@link #count} fills it and {@link #tick} counts again when the build, the
 * inventory or the linked chests changed. Rows keep their text between frames: drawing formats nothing.
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

    /** Ticks between two counts while things keep changing (AutoBuild placing, items moving). */
    private static final int RECOUNT_TICKS = 10;

    private final Minecraft mc;
    private long total, placed, toGet;
    private int cName, cNeed, cPlaced, cHave, cChests, cToGet;
    private String summary = "";

    // What the last count was of, and what it depended on: counted again only when one of these changes.
    private LoadedPlacement counted;
    private boolean visibleOnly;
    private ProgressTracker seenTracker;
    private long seenProgress = -1, seenChests = -1, seenLayers = -1;
    private int seenInventory;
    private boolean seenCountChests;
    private int sinceCount;
    // Per schematic: the block each item places (for its icon) and the entities' items; reading them walks every block.
    private Object iconsOf;
    private Map<String, BlockState> icons = Map.of();
    private List<ItemCount.Need> entityNeeds = List.of();

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
        return width - 18;
    }

    @Override
    protected int getScrollbarPosition() {
        return getX() + width - 7;
    }

    @Override
    protected void renderListBackground(GuiGraphics g) {
    }

    @Override
    protected void renderListSeparators(GuiGraphics g) {
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

    /** "1,234 of 5,000 blocks placed · 3 linked chests", as of the last count. */
    public String summary() {
        return summary;
    }

    /** Draws the column headings on the line at {@code y} (just above the list). */
    public void headings(GuiGraphics g, int y) {
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
        this.counted = placement;
        this.visibleOnly = visibleOnly;
        recount();
        setScrollAmount(0);
    }

    /**
     * Every tick: counts again (keeping the scroll position) when the build progress, the visible layers, the inventory,
     * the linked chests or the chests switch changed since the last count; at most every {@value #RECOUNT_TICKS} ticks.
     */
    public void tick() {
        if (++sinceCount < RECOUNT_TICKS || !changed()) return;
        double scroll = getScrollAmount();
        recount();
        setScrollAmount(scroll);
    }

    private boolean changed() {
        ProgressTracker t = counted == null ? null : counted.progress().tracker();
        return t != seenTracker || (t != null && t.version() != seenProgress)
                || (counted != null && visibleOnly && counted.layers.version() != seenLayers)
                || ChestTracker.get().chests().version() != seenChests || BlockCompanionClient.config().countChests != seenCountChests
                || inventoryHash() != seenInventory;
    }

    private void recount() {
        sinceCount = 0;
        LoadedPlacement placement = counted;
        List<Row> rows = new ArrayList<>();
        total = placed = toGet = 0;
        ProgressTracker tracker = placement == null ? null : placement.progress().tracker();
        seenTracker = tracker;
        seenProgress = tracker == null ? -1 : tracker.version();
        seenLayers = placement == null ? -1 : placement.layers.version();
        seenChests = ChestTracker.get().chests().version();
        seenCountChests = BlockCompanionClient.config().countChests;
        seenInventory = inventoryHash();
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
            schematicItems(placement);
            Map<String, Long> inv = inventory();
            Map<String, Long> chests = ChestTracker.get().totals();
            tracker.items(min, max).forEach((item, p) -> rows.add(new Row(item, p.needed(), p.placed(), inv.getOrDefault(item, 0L),
                    chests.getOrDefault(item, 0L), icons.get(item))));
            if (!visibleOnly) {
                for (ItemCount.Need n : entityNeeds) {
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
        summary = summaryLine();
    }

    /** The icons and the entities' items of the placement's schematic, worked out once per schematic. */
    private void schematicItems(LoadedPlacement placement) {
        var structure = placement.placement.structure();
        if (structure == iconsOf) return;
        iconsOf = structure;
        Map<String, BlockState> m = new HashMap<>();
        for (ItemCount.Need n : ItemCount.count(structure.stateCounts(), List.of(), false)) m.put(n.item(), n.icon());
        icons = m;
        entityNeeds = ItemCount.count(Map.of(), structure.entities(), false);
    }

    private String summaryLine() {
        int chests = ChestTracker.get().chests().size(), unknown = ChestTracker.get().chests().unknown();
        String sub = String.format(Locale.ROOT, "%,d of %,d blocks placed%s", placed, total, visibleOnly ? " (visible layers)" : "");
        if (chests > 0) sub += " · " + chests + (chests == 1 ? " linked chest" : " linked chests") + (unknown > 0 ? " (" + unknown + " not seen yet)" : "");
        return sub;
    }

    /** A number that changes when the inventory's items or counts do; no allocation. */
    private int inventoryHash() {
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int h = 1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            h = 31 * h + (st.isEmpty() ? 0 : System.identityHashCode(st.getItem()) * 67 + st.getCount());
        }
        return h;
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

    /** One row, with everything it shows worked out when it is made. */
    public final class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
        final Row r;
        final ItemStack stack;
        final String name, fitted, needed, placedText, have, chests, toGetText;
        final int placedColor, haveColor, chestsColor, toGetColor;
        final boolean done;
        final double covered;
        final Component tip;

        RowEntry(Row r) {
            this.r = r;
            ResourceLocation id = ResourceLocation.tryParse(r.item());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                stack = new ItemStack(item);
                name = stack.getHoverName().getString();
            } else {
                stack = ItemStack.EMPTY;
                name = Items.pretty(r.item());
            }
            fitted = Ui.fit(mc.font, name, cNeed - cName - 34);
            done = r.placed() >= r.needed();
            needed = fmt(r.needed());
            placedText = fmt(r.placed());
            have = fmt(r.have());
            chests = fmt(r.chests());
            toGetText = r.toGet() == 0 ? "✓" : fmt(r.toGet());
            placedColor = done ? Ui.GOOD : 0xFFB0D8FF;
            haveColor = r.have() > 0 ? Ui.TEXT : Ui.DIM;
            chestsColor = r.chests() > 0 ? 0xFFFFE27A : Ui.DIM;
            toGetColor = r.toGet() == 0 ? Ui.GOOD : 0xFFFF7070;
            covered = r.covered();
            tip = Component.literal(name + (r.toGet() > 0 ? "\nStill to get: " + Items.stacks(r.toGet(), r.item()) : "\nCovered"));
        }

        @Override
        public void render(GuiGraphics g, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = rowTop, left = rowLeft;
            var font = mc.font;
            if (hovering) Ui.fill(g, left - 2, top - 1, left + rowWidth + 2, top + 19, Ui.HOVER);
            if (!stack.isEmpty()) g.renderItem(stack, left + 1, top + 1);
            int ty = top + 2;
            Ui.text(g, font, fitted, cName, ty, Ui.TEXT);
            Ui.rightText(g, font, needed, cNeed, ty, Ui.TEXT);
            Ui.rightText(g, font, placedText, cPlaced, ty, placedColor);
            Ui.rightText(g, font, have, cHave, ty, haveColor);
            Ui.rightText(g, font, chests, cChests, ty, chestsColor);
            Ui.rightText(g, font, toGetText, cToGet, ty, toGetColor);
            // The bar under the name: how much of the item is placed, carried or in chests.
            int bx = cName, bw = cToGet - cName;
            if (done) Bars.solid(g, bx, top + 13, bw, 1, 0xFFFFD75A);
            else Bars.gradient(g, bx, top + 13, bw, covered);
            if (hovering) Ui.tooltip(g, font, tip, mouseX, mouseY);
        }

        @Override
        public Component getNarration() {
            return Component.literal(name);
        }
    }
}
