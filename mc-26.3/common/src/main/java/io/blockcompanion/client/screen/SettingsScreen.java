package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.ClientConfig;
import io.blockcompanion.client.Updates;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.core.hud.Palette;
import io.blockcompanion.core.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * Every BlockCompanion setting, on tabs, laid out like the game's own option screens. A change applies at once and is
 * saved; key bindings open the game's key binding screen, the HUD pieces the HUD editor, a colour the colour editor.
 * Each tab can be reset to its defaults, and the last open tab is remembered.
 */
public final class SettingsScreen extends OptionsSubScreen {
    private static final List<String> TOOLS = List.of("minecraft:stick", "minecraft:blaze_rod", "minecraft:bone", "minecraft:feather",
            "minecraft:wooden_axe", "");
    private static final int HEADER_HEIGHT = 58;

    /** The tabs, in order. */
    public enum Tab {
        GHOSTS("Ghosts"), BUILDING("Building"), HUD("HUD"), EFFECTS("Effects"), COLORS("Colours"), LINK("Link"), UPDATES("Updates");

        final String label;

        Tab(String label) {
            this.label = label;
        }

        static Tab parse(String s) {
            for (Tab t : values()) if (t.name().equalsIgnoreCase(s)) return t;
            return GHOSTS;
        }
    }

    private final Tab tab;
    private Button resetButton;
    private boolean resetArmed;
    private StringWidget updateStatus;
    private Button updateAction;
    private String shownUpdate = "";

    public SettingsScreen(Screen parent) {
        this(parent, Tab.parse(BlockCompanionClient.config().settingsTab));
    }

    public SettingsScreen(Screen parent, Tab tab) {
        super(parent, Minecraft.getInstance().options, Component.literal("BlockCompanion settings"));
        this.tab = tab;
    }

    /** A fresh copy of this screen on the same tab, showing current values. */
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

    // ---- header: title and tabs -------------------------------------------------------------------------------------

    @Override
    protected void addTitle() {
        layout.setHeaderHeight(HEADER_HEIGHT);
        LinearLayout header = LinearLayout.vertical().spacing(6);
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));
        LinearLayout tabs = header.addChild(LinearLayout.horizontal().spacing(2));
        Tab[] all = Tab.values();
        int w = Math.max(40, Math.min(66, (width - 16 - 2 * (all.length - 1)) / all.length));
        for (Tab t : all) {
            MutableComponent label = Component.literal(t.label);
            if (t == tab) label = label.withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE);
            tabs.addChild(Button.builder(label, b -> selectTab(t)).width(w).build());
        }
        layout.addToHeader(header);
    }

    private void selectTab(Tab t) {
        if (t == tab) return;
        config().settingsTab = t.name();
        changed();
        open(new SettingsScreen(lastScreen, t));
    }

    @Override
    protected void addFooter() {
        LinearLayout footer = LinearLayout.horizontal().spacing(8);
        resetButton = footer.addChild(Button.builder(Component.literal("Reset tab"), b -> reset())
                .tooltip(Tooltip.create(Component.literal("Puts everything on this tab back to its default.")))
                .width(150).build());
        footer.addChild(Button.builder(Component.translatable("gui.done"), b -> onClose()).width(150).build());
        layout.addToFooter(footer);
    }

    private void reset() {
        if (!resetArmed) {
            resetArmed = true;
            resetButton.setMessage(Component.literal("Click again to reset").withStyle(ChatFormatting.RED));
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
        open(reopen());
    }

    // ---- tab contents -----------------------------------------------------------------------------------------------

    @Override
    protected void addOptions() {
        ClientConfig c = config();
        switch (tab) {
            case GHOSTS -> {
                header("Look");
                row(slider("Ghost opacity", 0.3, 1, 0.05, c.ghostAlpha, v -> Math.round(v * 100) + "%", v -> c.ghostAlpha = (float) v,
                                "How solid missing blocks look."),
                        toggle("Shimmer", c.ghostShimmer, v -> c.ghostShimmer = v, "A slight tint and a slow pulse that mark ghosts as not built yet."));
                row(toggle("Chest, sign & bed shapes", c.ghostBlockEntities, v -> c.ghostBlockEntities = v,
                        "Draw chests, signs, beds, banners, heads... with their real shapes."), link("Ghost colours...", Tab.COLORS));
            }
            case BUILDING -> {
                header("Placing blocks");
                row(toggle("Easy place", c.easyPlace, v -> c.easyPlace = v, "Right-click a ghost with its block to place exactly that block there."),
                        toggle("Block to hand", c.easyPlaceAutoPick, v -> c.easyPlaceAutoPick = v,
                                "Easy place takes the ghost's block from your inventory when you don't hold it."));
                row(toggle("Pick ghosts", c.pickGhost, v -> c.pickGhost = v, "Middle click on a ghost picks its block."),
                        toggle("Material helper", c.materialHelper, v -> c.materialHelper = v,
                                "Holding a block marks the nearest ghosts that need it and shows how many are left."));
                row(slider("Helper marks", 8, 256, 8, c.materialHelperCells, v -> Integer.toString((int) v), v -> c.materialHelperCells = (int) v,
                        "How many ghosts the material helper marks at most."), null);

                header("Moving the schematic");
                row(modifier("Move: scroll +", c.moveModifier, v -> c.moveModifier = v, "Hold this and scroll while looking at a box to move it."),
                        modifier("Turn: scroll +", c.rotateModifier, v -> c.rotateModifier = v, "Hold this and scroll while looking at a box to turn it."));
                row(slider("Reach", 16, 256, 8, c.reach, v -> (int) v + " blocks", v -> c.reach = v, "How far away looking at a box still counts."),
                        Button.builder(Component.literal("Key binds..."), b -> open(new KeyBindsScreen(this, options)))
                                .tooltip(Tooltip.create(Component.literal("BlockCompanion's keys are in their own section."))).width(150).build());

                header("Selection tool and chests");
                row(tool(c), toggle("Count linked chests", c.countChests, v -> c.countChests = v,
                        "What your linked chests hold counts in the resource list, the info panel and Resource Tracker."));
                row(cycle("Fetch from chests", List.of(16, 32, 64, 128, 256, 576), c.restockCount, n -> n == 576 ? "9 stacks" : n + "",
                        v -> c.restockCount = v,
                        "How many easy place asks for at once when the block is in your linked chests (BlockCompanion servers)."), null);
            }
            case HUD -> {
                header("On screen");
                row(toggle("Info panel", c.progressHud, v -> c.progressHud = v, "The schematic, its progress, the layer and the held block."),
                        toggle("Crosshair hint", c.crosshairHint, v -> c.crosshairHint = v,
                                "Small text left of the crosshair: what a wrong block should be."));
                row(Button.builder(Component.literal("Move and size the HUD..."), b -> open(new HudEditorScreen(this)))
                        .tooltip(Tooltip.create(Component.literal("Drag the HUD pieces where you want them and set their size."))).width(310).build(), null);
            }
            case EFFECTS -> {
                header("While building");
                row(toggle("Sparkles", c.particles, v -> c.particles = v, "A few particles when a ghost is filled correctly."),
                        toggle("Sounds", c.sounds, v -> c.sounds = v,
                                "Soft chimes for correct blocks, a low note for your wrong ones. They follow the Blocks volume."));
                row(toggle("Rising chime", c.combo, v -> c.combo = v, "The chime climbs with quick correct blocks in a row."), null);
                header("Milestones");
                row(toggle("Layer done", c.layerCelebration, v -> c.layerCelebration = v, "A toast and sparkles when a layer is finished."),
                        toggle("Next layer when done", c.autoAdvanceLayer, v -> c.autoAdvanceLayer = v,
                                "In layer view, step to the next layer when the current one is finished."));
                row(toggle("Build finished", c.finishCelebration, v -> c.finishCelebration = v, "Fireworks and a summary when the whole build is done."),
                        null);
            }
            case COLORS -> addColors(c);
            case LINK -> {
                header("BlockDesigner");
                row(toggle("Live link", c.link, v -> c.link = v,
                                "Lets BlockDesigner's Resource Tracker find this game, send projects to it and follow your progress."),
                        toggle("Progress file", c.progressFile, v -> c.progressFile = v,
                                "Writes each build's progress to ~/.blockcompanion/progress for Resource Tracker."));
                List<String> apps = ClientLink.apps();
                String state = !ClientLink.running() ? "Link off" : apps.isEmpty() ? "Waiting for BlockDesigner" : "Connected: " + String.join(", ", apps);
                row(new StringWidget(310, 20, Component.literal(state).withStyle(apps.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.GREEN), font), null);
            }
            case UPDATES -> addUpdates(c);
        }
    }

    private void addColors(ClientConfig c) {
        Palette p = c.colors;
        header("Preset");
        List<String> names = new ArrayList<>();
        for (Palette.Preset preset : Palette.PRESETS) names.add(preset.name());
        Palette.Preset now = p.matchingPreset();
        String current = now == null ? "Custom" : now.name();
        if (now == null) names.add(current);
        row(cycle("Colours", names, current, s -> s, v -> {
                    for (Palette.Preset preset : Palette.PRESETS) if (preset.name().equals(v)) p.apply(preset);
                    minecraft.execute(() -> open(reopen()));
                }, "Sets every colour at once. Colour-blind safe uses purple and blue for wrong and in-the-way blocks."),
                new StringWidget(150, 20, swatchRow(p), font));
        header("Ghosts and marks");
        colorRows(p, Palette.Entry.GHOST, Palette.Entry.WRONG, Palette.Entry.EXTRA, Palette.Entry.HELPER, Palette.Entry.BLOCK_ENTITY);
        header("Boxes and outlines");
        colorRows(p, Palette.Entry.BOX, Palette.Entry.BOX_HOVER, Palette.Entry.BOX_LOCKED, Palette.Entry.SELECTION);
    }

    /** Every colour in a row of blocks, a quick look at the whole palette. */
    private static Component swatchRow(Palette p) {
        MutableComponent m = Component.empty();
        for (Palette.Entry e : Palette.Entry.values()) m.append(Component.literal("█").withColor(p.get(e)));
        return m;
    }

    private void colorRows(Palette p, Palette.Entry... entries) {
        for (int i = 0; i < entries.length; i += 2) row(colorButton(p, entries[i]), i + 1 < entries.length ? colorButton(p, entries[i + 1]) : null);
    }

    private Button colorButton(Palette p, Palette.Entry e) {
        MutableComponent label = Component.literal("██ ").withColor(p.get(e)).append(Component.literal(e.label).withStyle(ChatFormatting.WHITE));
        String tip = e.description + "\n" + Palette.hex(p.get(e)) + (p.isDefault(e) ? " (default)" : " (default " + Palette.hex(e.defaultRgb) + ")");
        return Button.builder(label, b -> open(new ColorScreen(this, e))).tooltip(Tooltip.create(Component.literal(tip))).width(150).build();
    }

    private void addUpdates(ClientConfig c) {
        Updater u = Updates.get();
        header("Updates");
        row(toggle("Check for updates", c.updateCheck, v -> {
                    c.updateCheck = v;
                    if (v) Updates.check();
                }, "Looks for a new release on GitHub when the game starts and every few hours."),
                toggle("Download automatically", c.updateAutoDownload, v -> {
                    c.updateAutoDownload = v;
                    if (v && u != null && u.state() == Updater.State.AVAILABLE) Updates.download();
                }, "Downloads a new version as soon as it is found. It installs when you quit the game."));
        String version = u == null ? "?" : u.currentVersion();
        row(new StringWidget(310, 20, Component.literal("This is BlockCompanion " + version).withStyle(ChatFormatting.GRAY), font), null);
        updateStatus = new StringWidget(310, 20, Component.empty(), font);
        row(updateStatus, null);
        updateAction = Button.builder(Component.empty(), b -> updateAction()).width(150).build();
        row(Button.builder(Component.literal("Check now"), b -> Updates.check()).width(150).build(), updateAction);
        row(Button.builder(Component.literal("Release page"), b -> Updater.openInBrowser(
                        u != null && u.latest() != null && !u.latest().page().isEmpty() ? u.latest().page() : Updates.RELEASES))
                .tooltip(Tooltip.create(Component.literal("Opens the release on GitHub, with what's new."))).width(150).build(), null);
        refreshUpdates();
    }

    private void updateAction() {
        Updater u = Updates.get();
        if (u == null || u.state() != Updater.State.AVAILABLE) return;
        if (u.canInstall()) Updates.download();
        else Updater.openInBrowser(u.latest() != null && !u.latest().page().isEmpty() ? u.latest().page() : Updates.RELEASES);
    }

    /** Keeps the Updates tab's status line and button in step with a running check or download. */
    private void refreshUpdates() {
        Updater u = Updates.get();
        if (updateStatus == null || u == null) return;
        Updater.State s = u.state();
        String key = s + "|" + u.message() + "|" + Math.round(u.progress() * 100);
        if (key.equals(shownUpdate)) return;
        shownUpdate = key;
        ChatFormatting color = switch (s) {
            case AVAILABLE, READY -> ChatFormatting.GREEN;
            case FAILED -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
        String message = u.message().isEmpty() ? "Not checked yet." : u.message();
        updateStatus.setMessage(Component.literal(message).withStyle(color));
        updateStatus.setTooltip(Tooltip.create(Component.literal(message)));
        String action;
        boolean active = false;
        switch (s) {
            case AVAILABLE -> {
                action = u.canInstall() ? "Download and install" : "Get it from GitHub";
                active = true;
            }
            case DOWNLOADING -> action = "Downloading " + Math.round(u.progress() * 100) + "%";
            case READY -> action = "Installs when you quit";
            case CHECKING -> action = "Checking...";
            default -> action = "No update to install";
        }
        updateAction.setMessage(Component.literal(action));
        updateAction.active = active;
    }

    @Override
    public void tick() {
        super.tick();
        refreshUpdates();
    }

    // ---- building blocks --------------------------------------------------------------------------------------------

    private void header(String text) {
        list.addHeader(Component.literal(text).withStyle(ChatFormatting.YELLOW));
    }

    private void row(AbstractWidget a, AbstractWidget b) {
        list.addSmall(a, b);
    }

    /** A button that jumps to another tab. */
    private Button link(String label, Tab to) {
        return Button.builder(Component.literal(label), b -> selectTab(to)).width(150).build();
    }

    private static CycleButton<Boolean> toggle(String label, boolean value, Consumer<Boolean> set, String tooltip) {
        return CycleButton.onOffBuilder(value).withTooltip(v -> Tooltip.create(Component.literal(tooltip)))
                .create(0, 0, 150, 20, Component.literal(label), (b, v) -> {
                    set.accept(v);
                    changed();
                });
    }

    private static <T> CycleButton<T> cycle(String label, List<T> values, T value, java.util.function.Function<T, String> name, Consumer<T> set,
                                            String tooltip) {
        List<T> all = new ArrayList<>(values);
        if (!all.contains(value)) all.add(value);
        return CycleButton.<T>builder(v -> Component.literal(name.apply(v)), value).withValues(all)
                .withTooltip(v -> Tooltip.create(Component.literal(tooltip)))
                .create(0, 0, 150, 20, Component.literal(label), (b, v) -> {
                    set.accept(v);
                    changed();
                });
    }

    private static CycleButton<ClientConfig.Modifier> modifier(String label, ClientConfig.Modifier value, Consumer<ClientConfig.Modifier> set,
                                                               String tooltip) {
        return cycle(label, List.of(ClientConfig.Modifier.values()), value,
                m -> m == ClientConfig.Modifier.NONE ? "Off" : m.name().charAt(0) + m.name().substring(1).toLowerCase(Locale.ROOT), set, tooltip);
    }

    private static CycleButton<String> tool(ClientConfig c) {
        return cycle("Selection tool", TOOLS, c.toolItem, SettingsScreen::toolName, v -> c.toolItem = v,
                "Left-click a block for corner 1, right-click for corner 2; sneak + right-click a chest to link it.");
    }

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

    private static AbstractSliderButton slider(String label, double min, double max, double step, double value, DoubleFunction<String> text,
                                               java.util.function.DoubleConsumer set, String tooltip) {
        AbstractSliderButton s = new AbstractSliderButton(0, 0, 150, 20, Component.empty(), (value - min) / (max - min)) {
            {
                updateMessage();
            }

            private double current() {
                double v = min + this.value * (max - min);
                return Math.max(min, Math.min(max, Math.round(v / step) * step));
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label + ": " + text.apply(current())));
            }

            @Override
            protected void applyValue() {
                set.accept(current());
                changed();
            }
        };
        s.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return s;
    }
}
