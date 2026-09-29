package io.blockcompanion.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.Keys;
import io.blockcompanion.core.hud.Palette;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * A short walk through the mod, a page at a time: loading a schematic, moving it into place, reading the ghosts,
 * building, materials and AutoBuild, saving and BlockDesigner. It opens by itself the first time you are in a world and
 * from the settings screen's Guide button. Key names and modifiers are the ones currently bound, in gold.
 */
public final class GuideScreen extends Screen {
    private record Page(String title, List<String> paragraphs) {
    }

    /** {key} is a key or modifier name (see {@link #token}); {wrong:text} and {extra:text} are text in the mark colours. */
    private static final List<Page> PAGES = List.of(
            new Page("Welcome to BlockCompanion", List.of(
                    "BlockCompanion shows a schematic in your world as ghost blocks, so you can build it block by block.",
                    "This guide takes a minute. It is in the settings too, under the Guide button.",
                    "Press {library} any time to open the BlockCompanion menu.")),
            new Page("Load a schematic", List.of(
                    "Put your BlockDesigner projects (.bdproj) and schematics (.schem, .litematic, .nbt) in the blockcompanion/schematics folder of your game folder. The menu's Open folder button takes you there.",
                    "Press {library}, pick a schematic under Source and press Load: it appears just in front of you.",
                    "With BlockDesigner open, From BlockDesigner loads the project you are working on.")),
            new Page("Move it into place", List.of(
                    "Hold the {tool}: it is the selection tool. Look at the schematic's box and it turns yellow.",
                    "{move}+scroll moves it one block towards the side of the box you look at: scroll up pushes it away, down pulls "
                            + "it closer. Standing inside it, it moves the way you look, which is the easy way to lift or lower it.",
                    "{rotate}+scroll turns it and {mirror} mirrors it.",
                    "When it sits right, press {lock} to lock it so nothing knocks it out of place. Ctrl+Z undoes a move.")),
            new Page("Read the ghosts", List.of(
                    "Ghosts are the blocks still to place. A block that is right shows nothing.",
                    "A {wrong:red} tint and outline mark a different block there, or the right one facing the wrong way. {extra:Orange} marks a block in the way.",
                    "{layer_up} and {layer_down} step through the layers, and {view} changes how much of the schematic you see.")),
            new Page("Build", List.of(
                    "Easy place ({easy_place} switches it): look at a ghost and right-click with its block to place exactly that block, turned the right way, even in mid-air.",
                    "Middle-click a ghost to pick its block. Holding a block marks the nearest ghosts that need it.",
                    "The panel in the bottom left shows how much is left, and the hint by the crosshair says what a block should be.")),
            new Page("Materials and AutoBuild", List.of(
                    "{resources} lists what the build still needs against your inventory and linked chests.",
                    "Hold the {tool} and {link}+right-click a chest to link it; its contents count as materials.",
                    "When your chests hold everything, Start AutoBuild in the menu's Resources step builds it for you, block by block.")),
            new Page("Save and BlockDesigner", List.of(
                    "With the {tool}, {corner}+left-click one corner and {corner}+right-click the other (or {select_corner} on each), then {save} saves the region as a .schem that BlockDesigner opens.",
                    "With BlockDesigner's BlockCompanion Plugin, the menu's BlockDesigner step links the game and the app live: send projects in and see what is built.")),
            new Page("That's it", List.of(
                    "Every option, the HUD layout and all the keys are in the settings: the menu's Settings button opens them.",
                    "This guide is there too. Happy building!")));

    private static final int PANEL_W = 340;

    private final Screen lastScreen;
    private int page;
    private int panelX, panelY, panelW, panelH;
    private List<FormattedCharSequence> lines = List.of();

    public GuideScreen(Screen parent) {
        this(parent, 0);
    }

    public GuideScreen(Screen parent, int page) {
        super(Component.literal("BlockCompanion guide"));
        this.lastScreen = parent;
        this.page = Math.max(0, Math.min(PAGES.size() - 1, page));
    }

    public static int pages() {
        return PAGES.size();
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, width - 2 * Ui.PAD);
        int textW = panelW - 4 * Ui.PAD;
        List<FormattedCharSequence> all = new ArrayList<>();
        List<String> paragraphs = PAGES.get(page).paragraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) all.add(FormattedCharSequence.EMPTY);
            all.addAll(font.split(styled(paragraphs.get(i)), textW));
        }
        lines = all;
        panelH = 3 * Ui.PAD + 35 + lines.size() * 10;
        panelX = (width - panelW) / 2;
        panelY = Math.max(Ui.TITLE_H + Ui.PAD, (height - panelH - Ui.BUTTON_H - Ui.PAD) / 2);

        int by = panelY + panelH + Ui.PAD, bw = (panelW - 2 * Ui.GAP) / 3;
        boolean last = page == PAGES.size() - 1;
        if (page == 0) {
            // The first page offers the tour in place of Back.
            Button tour = addRenderableWidget(Button.builder(Component.literal("Show me in the world").withColor(Ui.ACCENT),
                    b -> minecraft.setScreen(new TourScreen())).bounds(panelX, by, bw, Ui.BUTTON_H)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                            "A short tour with a demo schematic in front of you. Nothing in the world changes."))).build());
            tour.active = minecraft.level != null && minecraft.player != null;
        } else {
            addRenderableWidget(Ui.button("Back", null, panelX, by, bw, b -> go(page - 1)));
        }
        addRenderableWidget(Ui.button(last ? "Close" : "Skip guide", last ? null : "Close the guide. It is in the settings any time.",
                panelX + bw + Ui.GAP, by, bw, b -> onClose()));
        addRenderableWidget(Button.builder(Component.literal(last ? "Done" : "Next").withColor(Ui.ACCENT), b -> {
            if (last) onClose();
            else go(page + 1);
        }).bounds(panelX + panelW - bw, by, bw, Ui.BUTTON_H).build());
    }

    private void go(int to) {
        page = Math.max(0, Math.min(PAGES.size() - 1, to));
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_RIGHT) {
            go(page + 1);
            return true;
        }
        if (keyCode == InputConstants.KEY_LEFT) {
            go(page - 1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, panelX, panelY, panelW, panelH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Ui.titleBar(g, font, title, "Page " + (page + 1) + " of " + PAGES.size(), width);
        int x = panelX + 2 * Ui.PAD, y = panelY + 2 * Ui.PAD;
        Ui.section(g, font, PAGES.get(page).title(), x, y, panelW - 4 * Ui.PAD);
        y += 18;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, y, Ui.SOFT, false);
            y += 10;
        }
        // One dot per page, the current one gold.
        int dots = PAGES.size(), dx = panelX + (panelW - (dots * 10 - 3)) / 2, dy = panelY + panelH - Ui.PAD - 7;
        for (int i = 0; i < dots; i++) Ui.dot(g, dx + i * 10, dy, i == page ? Ui.ACCENT : Ui.DIM);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(lastScreen);
    }

    /** The paragraph with its {tokens} filled in and coloured. */
    public static Component styled(String text) {
        MutableComponent out = Component.empty();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('{', i);
            if (open < 0) {
                out.append(text.substring(i));
                break;
            }
            int close = text.indexOf('}', open);
            out.append(text.substring(i, open));
            out.append(token(text.substring(open + 1, close)));
            i = close + 1;
        }
        return out;
    }

    private static Component token(String name) {
        ClientConfig c = BlockCompanionClient.config();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            Palette.Entry e = name.startsWith("wrong") ? Palette.Entry.WRONG : Palette.Entry.EXTRA;
            return Component.literal(name.substring(colon + 1)).withColor(0xFF000000 | c.colors.get(e));
        }
        String s = switch (name) {
            case "tool" -> BlockCompanionClient.toolName();
            case "move" -> c.moveModifier.label();
            case "rotate" -> c.rotateModifier.label();
            case "corner" -> c.cornerModifier.label();
            case "link" -> c.linkModifier.label();
            // The game's own mouse actions, as "right-click" and so on while they are on their usual buttons.
            case "use" -> click(net.minecraft.client.Minecraft.getInstance().options.keyUse, "right-click");
            case "attack" -> click(net.minecraft.client.Minecraft.getInstance().options.keyAttack, "left-click");
            case "pick" -> click(net.minecraft.client.Minecraft.getInstance().options.keyPickItem, "middle-click");
            default -> key(name).getTranslatedKeyMessage().getString();
        };
        return Component.literal(s).withColor(Ui.ACCENT);
    }

    /** "right-click" (say) while the key is on its usual mouse button, else "press" and the key it was moved to. */
    private static String click(KeyMapping key, String usual) {
        return key.isDefault() ? usual : "press " + key.getTranslatedKeyMessage().getString();
    }

    private static KeyMapping key(String name) {
        return switch (name) {
            case "resources" -> Keys.RESOURCES;
            case "mirror" -> Keys.MIRROR;
            case "lock" -> Keys.LOCK;
            case "layer_up" -> Keys.LAYER_UP;
            case "layer_down" -> Keys.LAYER_DOWN;
            case "view" -> Keys.VIEW;
            case "easy_place" -> Keys.EASY_PLACE;
            case "select_corner" -> Keys.SELECT_CORNER;
            case "save" -> Keys.SAVE;
            default -> Keys.LIBRARY;
        };
    }
}
