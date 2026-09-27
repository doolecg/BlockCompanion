package io.blockcompanion.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.Keys;
import io.blockcompanion.client.Updates;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.update.Updater;
import io.blockcompanion.network.SyncKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every BlockCompanion setting, in sections listed down the left (or picked from a button on small screens). Each
 * section is one scrolling column of rows: the option's name with a short description under it on the left, its control
 * on the right. A change applies at once and is saved. The Keys section rebinds BlockCompanion's keys right here (click,
 * then press a key; Esc clears), the HUD pieces open the HUD editor, a colour the colour editor. Each section can be
 * reset to its defaults, and the last one open is remembered.
 */
public final class SettingsScreen extends Screen {
    private static final List<String> TOOLS = List.of("minecraft:stick", "minecraft:blaze_rod", "minecraft:bone", "minecraft:feather",
            "minecraft:wooden_axe", "");
    private static final int FOOTER_H = 30;

    /** The sections, in order. */
    public enum Tab {
        GHOSTS("Ghosts", "How missing blocks look"),
        BUILDING("Building", "Placing blocks and moving schematics"),
        HUD("HUD", "What shows on screen while building"),
        EFFECTS("Effects", "Sparkles, sounds and celebrations"),
        COLORS("Colours", "Ghost, mark and box colours"),
        KEYS("Keys", "BlockCompanion's key bindings"),
        LINK("BlockDesigner", "The live link to BlockDesigner"),
        UPDATES("Updates", "New versions from GitHub");

        final String label, blurb;

        Tab(String label, String blurb) {
            this.label = label;
            this.blurb = blurb;
        }

        static Tab parse(String s) {
            for (Tab t : values()) if (t.name().equalsIgnoreCase(s)) return t;
            return GHOSTS;
        }
    }

    private final Screen lastScreen;
    private Tab tab;
    private OptionList list;
    private Button resetButton;
    private boolean resetArmed;
    /** The key waiting for a new binding, or null. */
    private KeyMapping selectedKey;
    private int panelX, panelY, panelW, panelH, sideX, sideW;
    private boolean sidebar;

    public SettingsScreen(Screen parent) {
        this(parent, Tab.parse(BlockCompanionClient.config().settingsTab));
    }

    public SettingsScreen(Screen parent, Tab tab) {
        super(Component.literal("BlockCompanion settings"));
        this.lastScreen = parent;
        this.tab = tab;
    }

    /** A fresh copy of this screen on the same tab, showing current values (after the colour editor). */
    Screen reopen() {
        return new SettingsScreen(lastScreen, tab);
    }

    private static ClientConfig config() {
        return BlockCompanionClient.config();
    }

    private static void changed() {
        BlockCompanionClient.configChanged();
    }

    private void open(Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    // ---- layout -----------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        Tab[] tabs = Tab.values();
        int top = Ui.TITLE_H + Ui.PAD, bottom = height - FOOTER_H;
        // Tabs down the left when there is room for them, else one button that steps through them.
        sidebar = width >= 300 && bottom - top >= tabs.length * 22;
        sideX = Ui.PAD;
        sideW = sidebar ? Math.max(76, Math.min(112, width / 5)) : 0;
        int contentLeft = sidebar ? sideX + sideW + Ui.PAD : Ui.PAD;
        int room = width - contentLeft - Ui.PAD;
        panelW = Math.min(room, 460);
        panelX = contentLeft + (room - panelW) / 2;
        panelY = top + (sidebar ? 0 : 24);
        panelH = bottom - panelY - 2;

        if (sidebar) {
            for (int i = 0; i < tabs.length; i++) {
                Tab t = tabs[i];
                MutableComponent label = Component.literal(t.label);
                if (t == tab) label = label.withColor(Ui.ACCENT);
                Button b = addRenderableWidget(Button.builder(label, x -> selectTab(t)).bounds(sideX, top + i * 22, sideW, 20)
                        .tooltip(Tooltip.create(Component.literal(t.blurb))).build());
                b.active = t != tab;
            }
        } else {
            addRenderableWidget(CycleButton.<Tab>builder(t -> Component.literal(t.label).withColor(Ui.ACCENT), tab)
                    .withValues(tabs).create(panelX, top, panelW, 20, Component.literal("Section"), (b, t) -> selectTab(t)));
        }

        list = new OptionList(minecraft, panelX + 3, panelY + 3, panelW - 6, panelH - 6);
        addRenderableWidget(list);
        fill(list);

        int bw = Math.min(120, (width - 3 * Ui.PAD) / 2);
        int by = height - FOOTER_H + 5;
        resetButton = addRenderableWidget(Ui.button("Reset " + tab.label, "Puts everything in this section back to its default.",
                width - Ui.PAD - 2 * bw - Ui.GAP, by, bw, b -> reset()));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width - Ui.PAD - bw, by, bw, 20).build());
        resetArmed = false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, panelX, panelY, panelW, panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Ui.titleBar(g, font, title, tab.blurb, width);
        if (sidebar) {
            int i = List.of(Tab.values()).indexOf(tab);
            Ui.underline(g, sideX, Ui.TITLE_H + Ui.PAD + i * 22 + 19, sideW);
        }
        String note = "Changes apply at once and are saved.";
        if (Ui.PAD + font.width(note) + Ui.PAD < resetButton.getX()) Ui.text(g, font, note, Ui.PAD, height - FOOTER_H + 11, Ui.DIM);
    }

    @Override
    public void tick() {
        super.tick();
        if (list != null) list.tick();
    }

    private void selectTab(Tab t) {
        if (t == tab) return;
        tab = t;
        selectedKey = null;
        config().settingsTab = t.name();
        changed();
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(lastScreen);
    }

    // ---- reset ------------------------------------------------------------------------------------------------------

    private void reset() {
        if (!resetArmed) {
            resetArmed = true;
            resetButton.setMessage(Component.literal("Click again").withColor(Ui.BAD));
            return;
        }
        ClientConfig c = config(), d = new ClientConfig();
        switch (tab) {
            case GHOSTS -> {
                c.ghostAlpha = d.ghostAlpha;
                c.ghostShimmer = d.ghostShimmer;
                c.ghostBlockEntities = d.ghostBlockEntities;
            }
            case BUILDING -> {
                c.easyPlace = d.easyPlace;
                c.easyPlaceAutoPick = d.easyPlaceAutoPick;
                c.pickGhost = d.pickGhost;
                c.materialHelper = d.materialHelper;
                c.materialHelperCells = d.materialHelperCells;
                c.moveModifier = d.moveModifier;
                c.rotateModifier = d.rotateModifier;
                c.reach = d.reach;
                c.toolItem = d.toolItem;
                c.boxesAlways = d.boxesAlways;
                c.toolRequired = d.toolRequired;
                c.toolMode = d.toolMode;
                c.modeModifier = d.modeModifier;
                c.countChests = d.countChests;
                c.restockCount = d.restockCount;
            }
            case HUD -> {
                c.progressHud = d.progressHud;
                c.crosshairHint = d.crosshairHint;
            }
            case EFFECTS -> {
                c.particles = d.particles;
                c.sounds = d.sounds;
                c.combo = d.combo;
                c.layerCelebration = d.layerCelebration;
                c.autoAdvanceLayer = d.autoAdvanceLayer;
                c.finishCelebration = d.finishCelebration;
            }
            case COLORS -> c.colors.apply(Palette.DEFAULT);
            case KEYS -> {
                for (KeyMapping k : keys()) k.setKey(k.getDefaultKey());
                KeyMapping.resetMapping();
                minecraft.options.save();
            }
            case LINK -> {
                c.link = d.link;
                c.progressFile = d.progressFile;
            }
            case UPDATES -> {
                c.updateCheck = d.updateCheck;
                c.updateAutoDownload = d.updateAutoDownload;
            }
        }
        changed();
        rebuildWidgets();
    }

    // ---- sections ---------------------------------------------------------------------------------------------------

    private void fill(OptionList l) {
        ClientConfig c = config();
        switch (tab) {
            case GHOSTS -> {
                l.header("Look");
                l.option("Ghost opacity", "How solid missing blocks look.",
                        Ui.slider(0.3, 1, 0.05, c.ghostAlpha, v -> Math.round(v * 100) + "%", v -> c.ghostAlpha = (float) v, null));
                l.option("Shimmer", "A slight tint and a slow pulse that mark ghosts as not built yet.",
                        Ui.toggle(c.ghostShimmer, v -> c.ghostShimmer = v, null));
                l.option("Real shapes", "Chests, signs, beds, banners and heads drawn with their real shapes.",
                        Ui.toggle(c.ghostBlockEntities, v -> c.ghostBlockEntities = v, null));
                l.option("Ghost colours", "The tint, wrong and in-the-way colours are in the Colours section.",
                        Ui.button("Colours...", null, b -> selectTab(Tab.COLORS)));
            }
            case BUILDING -> {
                l.header("Placing blocks");
                l.option("Easy place", "Right-click a ghost with its block to place exactly that block there.",
                        Ui.toggle(c.easyPlace, v -> c.easyPlace = v, null));
                l.option("Block to hand", "Easy place takes the ghost's block from your inventory when you don't hold it.",
                        Ui.toggle(c.easyPlaceAutoPick, v -> c.easyPlaceAutoPick = v, null));
                l.option("Pick ghosts", "Middle click on a ghost picks its block.", Ui.toggle(c.pickGhost, v -> c.pickGhost = v, null));
                l.option("Material helper", "Holding a block marks the nearest ghosts that need it.",
                        Ui.toggle(c.materialHelper, v -> c.materialHelper = v, null));
                l.option("Helper marks", "How many ghosts the material helper marks at most.",
                        Ui.slider(8, 256, 8, c.materialHelperCells, v -> Integer.toString((int) v), v -> c.materialHelperCells = (int) v, null));

                l.header("Moving the schematic");
                l.option("Only with the tool", "Moving, turning and mirroring only work with the selection tool in hand. Without it, scrolling changes the hotbar as usual.",
                        Ui.toggle(c.toolRequired, v -> c.toolRequired = v, null));
                l.option("Tool scroll", "What plain scrolling does with the tool in hand while looking at a box: move, turn, mirror, step layers or show / hide.",
                        Ui.cycle(List.of(io.blockcompanion.core.placement.ToolMode.values()), c.toolMode, m -> m.label, v -> c.toolMode = v, null));
                l.option("Tool mode: scroll +", "Hold this and scroll with the tool in hand to switch what plain scrolling does.",
                        modifier(c.modeModifier, v -> c.modeModifier = v));
                l.option("Move: scroll +", "Hold this and scroll while looking at a box to move it.",
                        modifier(c.moveModifier, v -> c.moveModifier = v));
                l.option("Turn: scroll +", "Hold this and scroll while looking at a box to turn it.",
                        modifier(c.rotateModifier, v -> c.rotateModifier = v));
                l.option("Reach", "How far away looking at a box still counts.",
                        Ui.slider(16, 256, 8, c.reach, v -> (int) v + " blocks", v -> c.reach = v, null));
                l.option("Keys", "Every BlockCompanion key can be changed in the Keys section.", Ui.button("Keys...", null, b -> selectTab(Tab.KEYS)));

                l.header("Selection tool and chests");
                l.option("Selection tool", "Left-click a block for corner 1, right-click for corner 2; sneak + right-click a chest to link it.",
                        Ui.cycle(TOOLS, c.toolItem, SettingsScreen::toolName, v -> c.toolItem = v, null));
                l.option("Show boxes", "When the selection and the schematic boxes show: only while the selection tool is in your hand, or always. Ghosts always show.",
                        Ui.cycle(List.of(false, true), c.boxesAlways, v -> v ? "Always" : "Only with tool", v -> c.boxesAlways = v, null));
                l.option("Count linked chests", "What your linked chests hold counts in the resource list, the info panel and Resource Tracker.",
                        Ui.toggle(c.countChests, v -> c.countChests = v, null));
                l.option("Fetch from chests", "How many easy place asks for at once from your linked chests (BlockCompanion servers).",
                        Ui.cycle(List.of(16, 32, 64, 128, 256, 576), c.restockCount, n -> n == 576 ? "9 stacks" : n + "", v -> c.restockCount = v, null));
            }
            case HUD -> {
                l.header("On screen");
                l.option("Info panel", "The schematic, its progress, the layer and the held block.",
                        Ui.toggle(c.progressHud, v -> c.progressHud = v, null));
                l.option("Crosshair hint", "Small text left of the crosshair: what a wrong block should be.",
                        Ui.toggle(c.crosshairHint, v -> c.crosshairHint = v, null));
                l.option("Layout", "Drag the HUD pieces where you want them and set their size.",
                        Ui.button("Edit HUD...", null, b -> open(new HudEditorScreen(this))));
            }
            case EFFECTS -> {
                l.header("While building");
                l.option("Sparkles", "A few particles when a ghost is filled correctly.", Ui.toggle(c.particles, v -> c.particles = v, null));
                l.option("Sounds", "Soft chimes for correct blocks, a low note for wrong ones. They follow the Blocks volume.",
                        Ui.toggle(c.sounds, v -> c.sounds = v, null));
                l.option("Rising chime", "The chime climbs with quick correct blocks in a row.", Ui.toggle(c.combo, v -> c.combo = v, null));
                l.header("Milestones");
                l.option("Layer done", "A toast and sparkles when a layer is finished.",
                        Ui.toggle(c.layerCelebration, v -> c.layerCelebration = v, null));
                l.option("Next layer when done", "In layer view, step to the next layer when the current one is finished.",
                        Ui.toggle(c.autoAdvanceLayer, v -> c.autoAdvanceLayer = v, null));
                l.option("Build finished", "Fireworks and a summary when the whole build is done.",
                        Ui.toggle(c.finishCelebration, v -> c.finishCelebration = v, null));
            }
            case COLORS -> addColors(l, c);
            case KEYS -> addKeys(l);
            case LINK -> LinkPanel.add(l);
            case UPDATES -> addUpdates(l, c);
        }
    }

    private static CycleButton<ClientConfig.Modifier> modifier(ClientConfig.Modifier value, java.util.function.Consumer<ClientConfig.Modifier> set) {
        return Ui.cycle(List.of(ClientConfig.Modifier.values()), value,
                m -> m == ClientConfig.Modifier.NONE ? "Off" : m.name().charAt(0) + m.name().substring(1).toLowerCase(Locale.ROOT), set, null);
    }

    private void addColors(OptionList l, ClientConfig c) {
        Palette p = c.colors;
        l.header("Preset");
        List<String> names = new ArrayList<>();
        for (Palette.Preset preset : Palette.PRESETS) names.add(preset.name());
        Palette.Preset now = p.matchingPreset();
        String current = now == null ? "Custom" : now.name();
        if (now == null) names.add(current);
        l.status(() -> swatchRow(p), () -> "Sets every colour at once. Colour-blind safe uses purple and blue for wrong blocks.", () -> 0,
                Ui.cycle(names, current, s -> s, v -> {
                    for (Palette.Preset preset : Palette.PRESETS) if (preset.name().equals(v)) p.apply(preset);
                    minecraft.execute(this::rebuildWidgets);
                }, null));
        l.header("Ghosts and marks");
        for (Palette.Entry e : List.of(Palette.Entry.GHOST, Palette.Entry.WRONG, Palette.Entry.EXTRA, Palette.Entry.HELPER, Palette.Entry.BLOCK_ENTITY)) {
            colorRow(l, p, e);
        }
        l.header("Boxes and outlines");
        for (Palette.Entry e : List.of(Palette.Entry.BOX, Palette.Entry.BOX_HOVER, Palette.Entry.BOX_LOCKED, Palette.Entry.SELECTION)) colorRow(l, p, e);
    }

    /** Every colour in a row of blocks, a quick look at the whole palette. */
    private static Component swatchRow(Palette p) {
        MutableComponent m = Component.empty();
        for (Palette.Entry e : Palette.Entry.values()) m.append(Component.literal("█").withColor(p.get(e)));
        return m;
    }

    private void colorRow(OptionList l, Palette p, Palette.Entry e) {
        MutableComponent label = Component.literal("███ ").withColor(p.get(e)).append(Component.literal(Palette.hex(p.get(e))).withColor(Ui.SOFT));
        String tip = p.isDefault(e) ? "Default colour." : "Default: " + Palette.hex(e.defaultRgb);
        l.option(e.label, e.description, Button.builder(label, b -> open(new ColorScreen(this, e))).tooltip(Tooltip.create(Component.literal(tip)))
                .size(100, 20).build());
    }

    // ---- keys -------------------------------------------------------------------------------------------------------

    /** BlockCompanion's keys, the shared-space key included once it exists. */
    private static List<KeyMapping> keys() {
        List<KeyMapping> all = new ArrayList<>(Keys.ALL);
        if (SyncKeys.SHARED != null && !all.contains(SyncKeys.SHARED)) all.add(SyncKeys.SHARED);
        return all;
    }

    private void addKeys(OptionList l) {
        l.header("Keys");
        for (KeyMapping k : keys()) {
            Button key = Button.builder(Component.empty(), b -> selectedKey = k).size(100, 20).build();
            Button reset = Button.builder(Component.literal("Reset"), b -> {
                k.setKey(k.getDefaultKey());
                KeyMapping.resetMapping();
                minecraft.options.save();
            }).tooltip(Tooltip.create(Component.literal("Back to the default key."))).size(36, 20).build();
            Runnable update = () -> {
                key.setMessage(keyLabel(k));
                reset.active = !k.isDefault();
            };
            update.run();
            l.onTick(update);
            l.status(() -> Component.translatable(k.getName()), () -> keyNote(k), () -> 0, key, reset);
        }
        l.header("Everything else");
        l.option("All game controls", "The game's own key binding screen, with every mod's keys.",
                Ui.button("Controls...", null, b -> open(new KeyBindsScreen(this, minecraft.options))));
    }

    private Component keyLabel(KeyMapping k) {
        if (k == selectedKey) {
            return Component.literal("> ").withColor(Ui.ACCENT).append(k.getTranslatedKeyMessage().copy().withColor(Ui.TEXT))
                    .append(Component.literal(" <").withColor(Ui.ACCENT));
        }
        if (k.isUnbound()) return Component.literal("Not bound").withColor(Ui.MUTED);
        return conflict(k) != null ? k.getTranslatedKeyMessage().copy().withColor(Ui.BAD) : k.getTranslatedKeyMessage();
    }

    private String keyNote(KeyMapping k) {
        if (k == selectedKey) return "Press a key or mouse button; Esc leaves it unbound.";
        KeyMapping other = conflict(k);
        if (other != null) return "Also used by: " + Component.translatable(other.getName()).getString();
        return k.isDefault() ? "" : "Default: " + k.getDefaultKey().getDisplayName().getString();
    }

    private KeyMapping conflict(KeyMapping k) {
        if (k.isUnbound()) return null;
        for (KeyMapping other : minecraft.options.keyMappings) {
            // Debug (F3 +) and spectator keys only count in their own situation.
            if (other == k || other.getCategory() == KeyMapping.Category.DEBUG || other.getCategory() == KeyMapping.Category.SPECTATOR) continue;
            if (k.same(other)) return other;
        }
        return null;
    }

    private void bind(InputConstants.Key key) {
        selectedKey.setKey(key);
        selectedKey = null;
        KeyMapping.resetMapping();
        minecraft.options.save();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (selectedKey != null) {
            bind(event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (selectedKey != null) {
            bind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    // ---- updates ----------------------------------------------------------------------------------------------------

    private void addUpdates(OptionList l, ClientConfig c) {
        Updater u = Updates.get();
        l.header("Updates");
        l.option("Check for updates", "Looks for a new release on GitHub when the game starts and every few hours.",
                Ui.toggle(c.updateCheck, v -> {
                    c.updateCheck = v;
                    if (v) Updates.check();
                }, null));
        l.option("Download automatically", "Downloads a new version as soon as it is found. It installs when you quit the game.",
                Ui.toggle(c.updateAutoDownload, v -> {
                    c.updateAutoDownload = v;
                    if (v && u != null && u.state() == Updater.State.AVAILABLE) Updates.download();
                }, null));
        l.header("This version");
        Button action = Ui.button("", null, b -> updateAction());
        l.status(() -> Component.literal("BlockCompanion " + (u == null ? "?" : u.currentVersion())), SettingsScreen::updateMessage,
                SettingsScreen::updateColor, action);
        Runnable update = () -> refreshAction(action);
        update.run();
        l.onTick(update);
        l.option("Check again", "Asks GitHub for the latest release now.", Ui.button("Check now", null, b -> Updates.check()));
        l.option("What's new", "Opens the release on GitHub.", Ui.button("Release page", null, b -> Updater.openInBrowser(
                u != null && u.latest() != null && !u.latest().page().isEmpty() ? u.latest().page() : Updates.RELEASES)));
    }

    private static String updateMessage() {
        Updater u = Updates.get();
        if (u == null || u.message().isEmpty()) return "Not checked yet.";
        return u.message();
    }

    private static int updateColor() {
        Updater u = Updates.get();
        if (u == null) return Ui.DIM;
        return switch (u.state()) {
            case UP_TO_DATE, READY -> Ui.GOOD;
            case AVAILABLE -> Ui.ACCENT;
            case FAILED -> Ui.BAD;
            case CHECKING, DOWNLOADING -> Ui.WARN;
            default -> Ui.DIM;
        };
    }

    private void updateAction() {
        Updater u = Updates.get();
        if (u == null || u.state() != Updater.State.AVAILABLE) return;
        if (u.canInstall()) Updates.download();
        else Updater.openInBrowser(u.latest() != null && !u.latest().page().isEmpty() ? u.latest().page() : Updates.RELEASES);
    }

    /** Keeps the update button in step with a running check or download. */
    private static void refreshAction(Button b) {
        Updater u = Updates.get();
        if (u == null) {
            b.setMessage(Component.literal("Unavailable"));
            b.active = false;
            return;
        }
        String label;
        boolean active = false;
        switch (u.state()) {
            case AVAILABLE -> {
                label = u.canInstall() ? "Install" : "Get it";
                active = true;
            }
            case DOWNLOADING -> label = Math.round(u.progress() * 100) + "%";
            case READY -> label = "On quit";
            case CHECKING -> label = "Checking...";
            default -> label = "Up to date";
        }
        b.setMessage(Component.literal(label));
        b.active = active;
        b.setTooltip(Tooltip.create(Component.literal(switch (u.state()) {
            case AVAILABLE -> u.canInstall() ? "Downloads the new version; it installs when you quit the game." : "Opens the release page.";
            case READY -> "The new version installs when you quit the game.";
            default -> "No update to install.";
        })));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static String toolName(String id) {
        if (id == null || id.isBlank()) return "Off";
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return id;
        try {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(rl)).getHoverName().getString();
        } catch (RuntimeException e) {
            // Outside a world (the title screen's mod list) item data isn't loaded yet: name it from its id.
            StringBuilder name = new StringBuilder();
            for (String word : rl.getPath().split("_")) {
                if (word.isEmpty()) continue;
                if (!name.isEmpty()) name.append(' ');
                name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return name.toString();
        }
    }
}
