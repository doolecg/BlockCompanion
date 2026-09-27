package io.blockcompanion.client.hud;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.StateMapper;
import io.blockcompanion.client.chests.ChestTracker;
import io.blockcompanion.client.easyplace.EasyPlace;
import io.blockcompanion.client.tool.SelectionTool;
import io.blockcompanion.core.compare.Compare;
import io.blockcompanion.core.hud.HudLayout;
import io.blockcompanion.core.items.Items;
import io.blockcompanion.core.placement.Layers;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.placement.ToolMode;
import io.blockcompanion.core.progress.ProgressTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The HUD, in three movable pieces (see {@link HudLayout} and the HUD editor):
 * <ul>
 *   <li>the <b>info panel</b>, bottom left by default, in the game's tooltip frame like Jade's: the schematic, its
 *   progress bar, the current layer, the held block with how many are left and how many the linked chests hold, and
 *   what is locked;</li>
 *   <li>the <b>crosshair hint</b>: small text without a background just left of the crosshair, saying what a wrong
 *   block should be or which block a ghost is;</li>
 *   <li>the <b>tool panel</b>, bottom right by default, in the same frame while the selection tool is in hand: its mode,
 *   what that does, and the tool's controls.</li>
 * </ul>
 */
public final class Hud {
    private static final int BAR_W = 96;
    private static final int PAD = 4;
    private static final int GREY = 0xFFA8A8A8, WHITE = 0xFFFFFFFF, YELLOW = 0xFFFFE27A, GREEN = 0xFF8CE07A, RED = 0xFFFF8A80;

    private Hud() {
    }

    /** One line of the panel: an optional item icon, a text, an optional bar under it, and an optional grey note. */
    record Row(ItemStack icon, String text, int color, double bar, String note) {
        static Row text(String text, int color) {
            return new Row(ItemStack.EMPTY, text, color, -1, null);
        }

        int height() {
            int h = icon.isEmpty() ? 10 : 16;
            if (bar >= 0) h = Math.max(h, 10 + Bars.HEIGHT + 2);
            return h;
        }

        int width(Font font) {
            int w = (icon.isEmpty() ? 0 : 18) + font.width(text);
            if (bar >= 0) w = Math.max(w, (icon.isEmpty() ? 0 : 18) + BAR_W + 4 + font.width(pct(bar)));
            if (note != null) w += 6 + font.width(note);
            return w;
        }
    }

    public static void render(GuiGraphicsExtractor g, EasyPlace easy) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.player == null || mc.gui.screen() instanceof io.blockcompanion.client.screen.HudEditorScreen) return;
        ClientConfig config = BlockCompanionClient.config();
        HudLayout layout = config.hud;
        // The tool panel doesn't need a schematic: the selection tool works without one.
        if (BlockCompanionClient.toolPanelShown() && SelectionTool.holding(mc.player)) {
            drawPanel(g, mc.font, layout.get(HudLayout.Element.TOOL), toolRows(config));
        }
        LoadedPlacement lp = BlockCompanionClient.focus();
        if (lp == null) {
            List<LoadedPlacement> shown = BlockCompanionClient.shownHere();
            if (!shown.isEmpty()) lp = shown.get(shown.size() - 1);
        }
        if (lp == null) return;
        if (config.progressHud && layout.get(HudLayout.Element.PANEL).enabled()) drawPanel(g, mc.font, layout.get(HudLayout.Element.PANEL), rows(mc, lp));
        if (config.crosshairHint && layout.get(HudLayout.Element.HINT).enabled()) {
            Hint h = hint(mc, easy);
            if (h != null) drawHint(g, mc.font, layout.get(HudLayout.Element.HINT), h.text, h.color);
        }
    }

    // ---- the panel ------------------------------------------------------------------------------------------------

    static List<Row> rows(Minecraft mc, LoadedPlacement lp) {
        List<Row> rows = new ArrayList<>();
        List<LoadedPlacement> all = BlockCompanionClient.placements();
        String title = lp.shortName();
        String count = all.size() > 1 ? (all.indexOf(lp) + 1) + "/" + all.size() : null;
        ProgressTracker t = lp.progress().tracker();
        ItemStack icon = iconFor(lp);
        if (t == null) {
            rows.add(new Row(icon, title, WHITE, -1, count));
            return rows;
        }
        ProgressTracker.Totals all_ = t.totals();
        rows.add(new Row(icon, title, all_.complete() ? YELLOW : WHITE, -1, count));
        String left = all_.left() == 0 ? "done" : String.format(Locale.ROOT, "%,d left", all_.left());
        if (all_.wrong() > 0) left += String.format(Locale.ROOT, " · %,d wrong", all_.wrong());
        rows.add(new Row(ItemStack.EMPTY, left, all_.wrong() > 0 ? RED : GREY, all_.fraction(), null));

        Layers layers = lp.layers;
        if (!layers.showsAll()) {
            int level = layers.level();
            ProgressTracker.Totals lt = t.totals(level, level);
            String text = "Layer " + (level + 1) + " of " + lp.placement.localSizeY()
                    + (layers.mode() == Layers.Mode.SINGLE ? " (single)" : "") + " · Y " + (lp.placement.worldBox().minY() + level);
            rows.add(new Row(ItemStack.EMPTY, text, GREY, lt.fraction(), null));
        }

        String held = BlockCompanionClient.helperItem();
        if (held != null) {
            ProgressTracker.ItemProgress ip = t.item(held);
            if (ip.needed() > 0) {
                long inChests = ChestTracker.get().totals().getOrDefault(held, 0L);
                String text = ip.left() == 0 ? name(held) + ": all placed" : String.format(Locale.ROOT, "%s: %,d left", name(held), ip.left());
                if (!layers.showsAll()) {
                    ProgressTracker.ItemProgress onLayer = t.item(held, layers.level(), layers.level());
                    if (onLayer.needed() > 0) text += String.format(Locale.ROOT, " (%,d here)", onLayer.left());
                }
                String note = inChests > 0 ? String.format(Locale.ROOT, "%,d in chests", inChests) : null;
                rows.add(new Row(stack(held), text, ip.left() == 0 ? GREEN : YELLOW, -1, note));
            }
        }

        String auto = BlockCompanionClient.easyPlace().autoStatus();
        if (auto != null) rows.add(Row.text(auto, auto.equals("Auto place on") ? GREEN : GREY));
        List<String> status = new ArrayList<>();
        if (!lp.locks.isEmpty()) status.add(PlacementLock.describe(lp.locks));
        if (!lp.visible) status.add("hidden");
        if (lp.live) status.add("live from BlockDesigner");
        if (!BlockCompanionClient.config().easyPlace) status.add("easy place off");
        int chests = ChestTracker.get().chests().size();
        if (chests > 0 && BlockCompanionClient.config().countChests) status.add(chests + (chests == 1 ? " chest" : " chests"));
        if (lp.progress().pendingScans() > 0 && t.unseenSections() > 0) status.add("checking the world...");
        else if (t.lastSeenSections() > 0) status.add("some parts as last seen");
        if (!status.isEmpty()) rows.add(Row.text(String.join(" · ", status), 0xFF8A8A8A));
        return rows;
    }

    /** Sample rows for the HUD editor when nothing is loaded. */
    static List<Row> sampleRows() {
        return List.of(new Row(new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS), "Watchtower", WHITE, -1, "1/2"),
                new Row(ItemStack.EMPTY, "9,313 left · 12 wrong", RED, 0.64, null),
                new Row(ItemStack.EMPTY, "Layer 5 of 32 · Y 68", GREY, 0.3, null),
                new Row(new ItemStack(net.minecraft.world.item.Items.STONE_BRICKS), "Stone Bricks: 214 left", YELLOW, -1, "320 in chests"),
                Row.text("Locked in place · 3 chests", 0xFF8A8A8A));
    }

    /** The panel's size at scale 1. */
    static int[] measure(Font font, List<Row> rows) {
        int w = 0, h = 0;
        for (Row r : rows) {
            w = Math.max(w, r.width(font));
            h += r.height() + 1;
        }
        return new int[]{w + 2 * PAD, h - 1 + 2 * PAD};
    }

    /** Draws the panel where the layout puts it; returns its box on screen {x, y, w, h}. */
    static int[] drawPanel(GuiGraphicsExtractor g, Font font, HudLayout.Placement at, List<Row> rows) {
        int[] size = measure(font, rows);
        float s = at.scale();
        int w = Math.round(size[0] * s), h = Math.round(size[1] * s);
        int[] tl = at.topLeft(g.guiWidth(), g.guiHeight(), w, h);
        g.pose().pushMatrix();
        g.pose().translate(tl[0], tl[1]);
        g.pose().scale(s, s);
        // The frame sits 3 px inside the box: the game draws a tooltip's frame around the content.
        TooltipRenderUtil.extractTooltipBackground(g, 3, 3, size[0] - 6, size[1] - 6, null);
        int y = PAD;
        for (Row r : rows) {
            int x = PAD;
            int rh = r.height();
            if (!r.icon().isEmpty()) {
                g.item(r.icon(), x, y + (rh - 16) / 2);
                x += 18;
            }
            int ty = r.bar() >= 0 ? y : y + (rh - 8) / 2;
            g.text(font, r.text(), x, ty, r.color(), true);
            if (r.note() != null) g.text(font, r.note(), x + font.width(r.text()) + 6, ty, GREY, true);
            if (r.bar() >= 0) {
                int by = y + 10;
                if (r.bar() >= 1) Bars.solid(g, x, by, BAR_W, 1, 0xFFFFD75A);
                else Bars.gradient(g, x, by, BAR_W, r.bar());
                g.text(font, pct(r.bar()), x + BAR_W + 4, by - 2, WHITE, true);
            }
            y += rh + 1;
        }
        g.pose().popMatrix();
        return new int[]{tl[0], tl[1], w, h};
    }

    // ---- the tool panel -------------------------------------------------------------------------------------------

    /** The tool panel: the tool and its mode, what the mode does, then the controls that are switched on. */
    static List<Row> toolRows(ClientConfig config) {
        ToolMode m = config.toolMode;
        List<Row> rows = new ArrayList<>();
        ItemStack tool = stack(config.toolItem);
        rows.add(new Row(tool.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.STICK) : tool, "Tool: " + m.label, WHITE, -1,
                (m.ordinal() + 1) + "/" + ToolMode.values().length));
        rows.add(Row.text(m.hint, 0xFFE0E8FF));
        if (m != ToolMode.MOVE && BlockCompanionClient.selectionLookedAt()) rows.add(Row.text("The selection can only be moved", YELLOW));
        for (String c : controls(config)) rows.add(Row.text(c, GREY));
        return rows;
    }

    /** "Shift+scroll: move", "Ctrl+scroll: turn 90°"...: the tool's controls, those switched off left out. */
    private static List<String> controls(ClientConfig config) {
        ClientConfig.Modifier move = config.moveModifier, turn = config.rotateModifier;
        List<String> out = new ArrayList<>();
        if (move != ClientConfig.Modifier.NONE) out.add(move.label() + "+scroll: " + config.toolMode.verb);
        if (turn != ClientConfig.Modifier.NONE && turn != move) out.add(turn.label() + "+scroll: turn 90°");
        if (move != ClientConfig.Modifier.NONE && turn != ClientConfig.Modifier.NONE && turn != move) {
            out.add(turn.label() + "+" + move.label() + "+scroll: switch mode");
        }
        if (!io.blockcompanion.client.Keys.VIEW.isUnbound()) out.add(BlockCompanionClient.keyName("view") + ": cycle views");
        if (config.cornerModifier != ClientConfig.Modifier.NONE) out.add(config.cornerModifier.label() + "+left / right-click: corners");
        if (config.clearModifier != ClientConfig.Modifier.NONE) out.add(config.clearModifier.label() + "+right-click: clear selection");
        if (config.linkModifier != ClientConfig.Modifier.NONE) out.add(config.linkModifier.label() + "+right-click a chest: link it");
        if (!io.blockcompanion.client.Keys.UNDO.isUnbound()) out.add("Ctrl+" + BlockCompanionClient.keyName("undo") + ": undo");
        return out;
    }

    // ---- the crosshair hint ---------------------------------------------------------------------------------------

    record Hint(String text, int color) {
    }

    static Hint hint(Minecraft mc, EasyPlace easy) {
        String h = easy.hint();
        if (h != null) return new Hint(h, YELLOW);
        String wrong = wrongHint(mc);
        if (wrong != null) return new Hint(wrong, RED);
        EasyPlace.Target t = easy.target();
        if (t != null) return new Hint(describe(t.want(), t.wantMc()), 0xFFE0E8FF);
        return null;
    }

    /** Draws the hint's text where the layout puts it; returns its box {x, y, w, h}. */
    static int[] drawHint(GuiGraphicsExtractor g, Font font, HudLayout.Placement at, String text, int color) {
        float s = at.scale();
        int w = Math.round(font.width(text) * s), h = Math.round(8 * s);
        int[] tl = at.topLeft(g.guiWidth(), g.guiHeight(), w, h);
        g.pose().pushMatrix();
        g.pose().translate(tl[0], tl[1]);
        g.pose().scale(s, s);
        g.text(font, text, 0, 0, color, true);
        g.pose().popMatrix();
        return new int[]{tl[0], tl[1], w, h};
    }

    /** "Should be Oak Stairs · facing north" while looking at a wrong block of a shown schematic. */
    private static String wrongHint(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        net.minecraft.core.BlockPos p = hit.getBlockPos();
        for (LoadedPlacement lp : BlockCompanionClient.shownHere()) {
            if (!lp.placement.worldBox().contains(p.getX(), p.getY(), p.getZ())) continue;
            if (!lp.layers.isVisible(p.getY() - lp.placement.worldBox().minY())) continue;
            io.blockcompanion.core.model.BlockState want = lp.placement.stateAt(p.getX(), p.getY(), p.getZ());
            io.blockcompanion.core.model.BlockState have = StateMapper.toCore(mc.level.getBlockState(p));
            Compare.Result r = Compare.classify(want, have);
            if (r == Compare.Result.EXTRA) return "Not in the schematic";
            if (r == Compare.Result.WRONG) return "Should be " + describe(want, StateMapper.toMc(want));
        }
        return null;
    }

    /** A block's name and how it faces: "Oak Stairs · facing north, top". */
    static String describe(io.blockcompanion.core.model.BlockState want, net.minecraft.world.level.block.state.BlockState mcWant) {
        String name = mcWant == null ? Items.pretty(want.name()) : mcWant.getBlock().getName().getString();
        StringBuilder how = new StringBuilder();
        for (String prop : new String[]{"facing", "half", "type", "axis", "hinge", "face"}) {
            String v = want.get(prop);
            if (v == null) continue;
            if (!how.isEmpty()) how.append(", ");
            how.append(prop.equals("axis") ? "axis " + v : prop.equals("facing") ? "facing " + v : v);
        }
        return name + (how.isEmpty() ? "" : " · " + how);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    static String pct(double f) {
        if (f >= 1) return "100%";
        return String.format(Locale.ROOT, "%.0f%%", Math.min(99, Math.floor(f * 100)));
    }

    private static ItemStack stack(String id) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.getValue(rl);
        return item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String name(String id) {
        ItemStack s = stack(id);
        return s.isEmpty() ? Items.pretty(id) : s.getHoverName().getString();
    }

    private static ProgressTracker iconTracker;
    private static ItemStack icon = ItemStack.EMPTY;

    /** The panel's icon: the schematic's most common block (worked out once per schematic). */
    private static ItemStack iconFor(LoadedPlacement lp) {
        ProgressTracker t = lp.progress().tracker();
        if (t == null) return new ItemStack(net.minecraft.world.item.Items.MAP);
        if (t == iconTracker) return icon;
        iconTracker = t;
        String best = null;
        long most = -1;
        for (var e : t.items().entrySet()) {
            if (e.getValue().needed() > most) {
                most = e.getValue().needed();
                best = e.getKey();
            }
        }
        ItemStack s = best == null ? ItemStack.EMPTY : stack(best);
        icon = s.isEmpty() ? new ItemStack(net.minecraft.world.item.Items.MAP) : s;
        return icon;
    }
}
