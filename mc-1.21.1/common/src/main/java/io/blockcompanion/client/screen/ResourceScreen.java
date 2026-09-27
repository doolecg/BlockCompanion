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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
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
 * What a build needs: every item with how many the blocks need, how many are placed, how many you carry, how many your
 * linked chests hold, and how many are still to get (needed - placed - carried - in chests), most still to get first.
 * Each row's bar fills red to green as the item is covered. Counts follow the live build progress; Refresh re-reads the
 * inventory and chests.
 */
public final class ResourceScreen extends Screen {
    private final Screen parent;
    private LoadedPlacement placement;

    /** One row: an item, needed, placed correctly, carried, in linked chests. */
    private record Row(String item, long needed, long placed, long have, long chests, BlockState icon) {
        long toGet() {
            return Math.max(0, needed - placed - have - chests);
        }

        double covered() {
            return needed == 0 ? 1 : Math.min(1, (placed + have + chests) / (double) needed);
        }
    }

    private RowList list;
    private long total, placed;
    /** Only the visible layers instead of the whole schematic. */
    private boolean visibleOnly;
    private int cName, cNeed, cPlaced, cHave, cChests, cToGet;

    public ResourceScreen(Screen parent, LoadedPlacement placement) {
        super(Component.translatable("blockcompanion.resources.title"));
        this.parent = parent;
        this.placement = placement;
    }

    @Override
    protected void init() {
        int margin = Math.max(8, (width - 460) / 2);
        list = new RowList(minecraft, width - 2 * margin, height - 48 - 36, 48);
        list.setX(margin);
        addRenderableWidget(list);
        int right = list.getX() + list.getRowWidth() + (list.getWidth() - list.getRowWidth()) / 2 - 8;
        cToGet = right;
        cChests = right - 48;
        cHave = right - 96;
        cPlaced = right - 144;
        cNeed = right - 192;
        cName = list.getX() + 26;

        List<LoadedPlacement> all = BlockCompanionClient.placements();
        if (placement == null || !all.contains(placement)) placement = all.isEmpty() ? null : all.get(0);
        int w = 100, y = height - 28;
        int x = width / 2 - (4 * w + 3 * 4) / 2;
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
                .create(x + (w + 4), y, w, 20, Component.empty(), (b, v) -> {
                    visibleOnly = v;
                    recount();
                }));
        addRenderableWidget(Button.builder(Component.translatable("blockcompanion.library.refresh"), b -> recount())
                .tooltip(Tooltip.create(Component.literal("Count your inventory and linked chests again."))).bounds(x + 2 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 3 * (w + 4), y, w, 20).build());
        recount();
    }

    private void recount() {
        List<Row> rows = new ArrayList<>();
        total = placed = 0;
        ProgressTracker tracker = placement == null ? null : placement.progress().tracker();
        if (tracker != null && minecraft.player != null) {
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
        list.set(rows);
    }

    private Map<String, Long> inventory() {
        Map<String, Long> have = new HashMap<>();
        Inventory inv = minecraft.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            have.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), (long) st.getCount(), Long::sum);
        }
        return have;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        String name = placement == null ? "nothing loaded" : placement.shortName();
        g.drawCenteredString(font, title.getString() + ": " + name, width / 2, 8, 0xFFFFFFFF);
        int chests = ChestTracker.get().chests().size(), unknown = ChestTracker.get().chests().unknown();
        String sub = String.format(Locale.ROOT, "%,d of %,d blocks placed%s", placed, total, visibleOnly ? " (visible layers)" : "");
        if (chests > 0) sub += " · " + chests + (chests == 1 ? " linked chest" : " linked chests") + (unknown > 0 ? " (" + unknown + " not seen yet)" : "");
        g.drawCenteredString(font, sub, width / 2, 20, 0xFF9A9A9A);
        int hy = 36;
        g.drawString(font, "Item", cName, hy, 0xFFB0B0B0, true);
        rightText(g, "Needed", cNeed, hy, 0xFFB0B0B0);
        rightText(g, "Placed", cPlaced, hy, 0xFFB0B0B0);
        rightText(g, "Have", cHave, hy, 0xFFB0B0B0);
        rightText(g, "Chests", cChests, hy, 0xFFB0B0B0);
        rightText(g, "To get", cToGet, hy, 0xFFB0B0B0);
        if (list.children().isEmpty()) {
            g.drawCenteredString(font, total > 0 ? "Everything is placed" : "Nothing to count", width / 2, list.getY() + 10, 0xFF80FF80);
        }
    }

    private void rightText(GuiGraphics g, String s, int right, int y, int color) {
        g.drawString(font, s, right - font.width(s), y, color, true);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class RowList extends ObjectSelectionList<RowEntry> {
        RowList(Minecraft mc, int w, int h, int y) {
            super(mc, w, h, y, 22);
        }

        void set(List<Row> rows) {
            clearEntries();
            for (Row r : rows) addEntry(new RowEntry(r));
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return width - 14;
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }
    }

    private final class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
        final Row r;
        final ItemStack stack;
        final String name;

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
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (!stack.isEmpty()) g.renderItem(stack, left + 2, top + 1);
            int ty = top + 2;
            g.drawString(font, font.plainSubstrByWidth(name, cNeed - cName - 50), cName, ty, 0xFFFFFFFF, false);
            boolean done = r.placed() >= r.needed();
            rightText(g, fmt(r.needed()), cNeed, ty, 0xFFFFFFFF);
            rightText(g, fmt(r.placed()), cPlaced, ty, done ? 0xFF80FF80 : 0xFFB0D8FF);
            rightText(g, fmt(r.have()), cHave, ty, r.have() > 0 ? 0xFFFFFFFF : 0xFF707070);
            rightText(g, fmt(r.chests()), cChests, ty, r.chests() > 0 ? 0xFFFFE27A : 0xFF707070);
            rightText(g, r.toGet() == 0 ? "✓" : fmt(r.toGet()), cToGet, ty, r.toGet() == 0 ? 0xFF80FF80 : 0xFFFF7070);
            // The bar under the name: how much of the item is placed, carried or in chests.
            int bx = cName, bw = cToGet - cName;
            if (done) Bars.solid(g, bx, top + 13, bw, 1, 0xFFFFD75A);
            else Bars.gradient(g, bx, top + 13, bw, r.covered());
            if (hovering && r.toGet() > 0) {
                setTooltipForNextRenderPass(Component.literal("Still to get: " + Items.stacks(r.toGet(), r.item())));
            }
        }

        private String fmt(long n) {
            return String.format(Locale.ROOT, "%,d", n);
        }

        @Override
        public Component getNarration() {
            return Component.literal(name);
        }
    }
}
