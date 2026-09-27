package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.client.hud.Bars;
import io.blockcompanion.client.link.ClientLink;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.placement.PlacementLock;
import io.blockcompanion.core.progress.ProgressTracker;
import io.blockcompanion.core.project.BdProject;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The schematics: on the left every file in the library (BlockDesigner projects and Sponge schematics first, green),
 * on the right what is loaded in this world. Load adds a schematic in front of you without replacing the others; the
 * loaded list shows, hides, locks, follows BlockDesigner and unloads each one. Grab from BlockDesigner asks the app for
 * the project it has open.
 */
public final class LibraryScreen extends Screen {
    /** The lock presets the Lock button cycles through. */
    private enum LockPreset {
        NONE("Unlocked", Set.of()),
        POSITION("Position", EnumSet.of(PlacementLock.POSITION)),
        PLACE("In place", PlacementLock.IN_PLACE),
        ALL("Everything", PlacementLock.ALL),
        CUSTOM("Custom", Set.of());

        final String label;
        final Set<PlacementLock> locks;

        LockPreset(String label, Set<PlacementLock> locks) {
            this.label = label;
            this.locks = locks;
        }

        static LockPreset of(Set<PlacementLock> locks) {
            for (LockPreset p : values()) if (p != CUSTOM && p.locks.equals(locks)) return p;
            return CUSTOM;
        }
    }

    private final Screen parent;
    private FileList files;
    private LoadedList loaded;
    private String error;
    private Button loadButton, showButton, unloadButton, hereButton;
    private CycleButton<LockPreset> lockButton;
    private CycleButton<Boolean> liveButton;
    /** Layer lists of projects, read once per file version. */
    private final Map<String, String> projectInfo = new HashMap<>();

    public LibraryScreen(Screen parent) {
        super(Component.translatable("blockcompanion.library.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = 44, bottom = height - 58;
        int gap = 8, margin = 8;
        int leftW = (width - 2 * margin - gap) * 11 / 20, rightW = width - 2 * margin - gap - leftW;
        files = new FileList(minecraft, leftW, bottom - top, top);
        files.setX(margin);
        loaded = new LoadedList(minecraft, rightW, bottom - top, top);
        loaded.setX(margin + leftW + gap);
        addRenderableWidget(files);
        addRenderableWidget(loaded);
        refresh();
        refreshLoaded();

        // Under the library: load the selected file.
        int by = height - 52;
        loadButton = addRenderableWidget(Button.builder(Component.literal("Load"), b -> loadSelected()).bounds(margin, by, 70, 20)
                .tooltip(Tooltip.create(Component.literal("Load the selected schematic in front of you (the others stay loaded)."))).build());
        addRenderableWidget(Button.builder(Component.translatable("blockcompanion.library.open_folder"),
                b -> Blaze3D.openPath(BlockCompanionClient.library().root())).bounds(margin + 74, by, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("blockcompanion.library.refresh"), b -> {
            refresh();
            refreshLoaded();
        }).bounds(margin + 158, by, 60, 20).build());

        // Under the loaded list: what to do with the selected placement.
        int rx = loaded.getX(), rw = loaded.getWidth();
        int bw = (rw - 3 * 4) / 4;
        showButton = addRenderableWidget(Button.builder(Component.literal("Hide"), b -> toggleShown()).bounds(rx, by, bw, 20).build());
        lockButton = addRenderableWidget(CycleButton.<LockPreset>builder(p -> Component.literal(p.label), LockPreset.NONE)
                .withValues(LockPreset.NONE, LockPreset.POSITION, LockPreset.PLACE, LockPreset.ALL)
                .withTooltip(p -> Tooltip.create(Component.literal(lockTooltip(p))))
                .create(rx + (bw + 4), by, bw, 20, Component.literal("Lock"), (b, p) -> setLocks(p)));
        hereButton = addRenderableWidget(Button.builder(Component.literal("Bring here"), b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp != null) BlockCompanionClient.bringHere(lp);
        }).bounds(rx + 2 * (bw + 4), by, bw, 20).tooltip(Tooltip.create(Component.literal("Move it to just in front of you."))).build());
        unloadButton = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.library.unload"), b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp == null) return;
            BlockCompanionClient.unload(lp, true);
            BlockCompanionClient.actionBar("Unloaded " + lp.shortName());
            refreshLoaded();
        }).bounds(rx + 3 * (bw + 4), by, bw, 20).build());

        // The bottom row.
        int w = 96, y = height - 28, n = 5;
        int x = width / 2 - (n * w + (n - 1) * 4) / 2;
        liveButton = addRenderableWidget(CycleButton.onOffBuilder(true)
                .withTooltip(v -> Tooltip.create(Component.literal("Follow BlockDesigner: reload this project when the app sends a new version.")))
                .create(x, y, w, 20, Component.literal("Live"), (b, v) -> {
                    LoadedPlacement lp = selectedPlacement();
                    if (lp == null) return;
                    lp.live = v;
                    BlockCompanionClient.changed(lp);
                }));
        Button grab = addRenderableWidget(Button.builder(Component.literal("Grab from BD"), b -> {
            BlockCompanionClient.grab();
            onClose();
        }).bounds(x + (w + 4), y, w, 20).build());
        List<String> apps = ClientLink.apps();
        grab.setTooltip(Tooltip.create(Component.literal(apps.isEmpty()
                ? "Ask BlockDesigner for the project it has open. Open Resource Tracker's Game link in BlockDesigner first."
                : "Ask " + String.join(", ", apps) + " for the project open in BlockDesigner.")));
        grab.active = ClientLink.running();
        addRenderableWidget(Button.builder(Component.literal("Resources"), b -> {
            LoadedPlacement lp = selectedPlacement();
            if (lp != null) minecraft.gui.setScreen(new ResourceScreen(this, lp));
        }).bounds(x + 2 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Settings"), b -> minecraft.gui.setScreen(new SettingsScreen(this)))
                .bounds(x + 3 * (w + 4), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 4 * (w + 4), y, w, 20).build());
        updateButtons();
    }

    private static String lockTooltip(LockPreset p) {
        return switch (p) {
            case NONE -> "Nothing locked: scroll to move and turn it, M mirrors it.";
            case POSITION -> "It can't be moved (turning, mirroring and layers still work).";
            case PLACE -> "Locked in place: it can't be moved, turned or mirrored.";
            case ALL -> "Everything: in place, and the layer view can't change either.";
            case CUSTOM -> "Some parts locked.";
        };
    }

    private void refresh() {
        try {
            files.set(BlockCompanionClient.library().list());
            error = null;
        } catch (IOException e) {
            files.set(List.of());
            error = e.getMessage();
        }
    }

    private void refreshLoaded() {
        loaded.set(BlockCompanionClient.placements());
        updateButtons();
    }

    private LoadedPlacement selectedPlacement() {
        LoadedEntry e = loaded == null ? null : loaded.getSelected();
        return e == null ? null : e.lp;
    }

    private void updateButtons() {
        if (showButton == null) return;
        LoadedPlacement lp = selectedPlacement();
        boolean any = lp != null;
        showButton.active = lockButton.active = unloadButton.active = liveButton.active = any;
        hereButton.active = any && BlockCompanionClient.here(lp) && !lp.locked(PlacementLock.POSITION);
        loadButton.active = files.getSelected() != null;
        if (any) {
            showButton.setMessage(Component.literal(lp.visible ? "Hide" : "Show"));
            LockPreset p = LockPreset.of(lp.locks);
            if (p != LockPreset.CUSTOM) lockButton.setValue(p);
            liveButton.setValue(lp.live);
            liveButton.active = lp.fromBlockDesigner();
        }
    }

    private void toggleShown() {
        LoadedPlacement lp = selectedPlacement();
        if (lp == null) return;
        lp.visible = !lp.visible;
        BlockCompanionClient.changed(lp);
        updateButtons();
    }

    private void setLocks(LockPreset p) {
        LoadedPlacement lp = selectedPlacement();
        if (lp == null) return;
        lp.locks.clear();
        lp.locks.addAll(p.locks);
        BlockCompanionClient.changed(lp);
        updateButtons();
    }

    private void loadSelected() {
        FileEntry e = files.getSelected();
        if (e == null) return;
        if (BlockCompanionClient.load(e.entry.name())) {
            refreshLoaded();
            LoadedPlacement lp = BlockCompanionClient.active();
            for (LoadedEntry le : loaded.children()) if (le.lp == lp) loaded.setSelected(le);
            updateButtons();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        g.centeredText(font, font.plainSubstrByWidth(BlockCompanionClient.library().root().toString(), width - 20), width / 2, 20, 0xFF8A8A8A);
        g.text(font, "Library", files.getX() + 2, 33, 0xFFE0E0E0, true);
        int count = BlockCompanionClient.placements().size();
        g.text(font, "Loaded here" + (count > 0 ? " (" + count + ")" : ""), loaded.getX() + 2, 33, 0xFFE0E0E0, true);
        if (error != null) g.text(font, error, files.getX() + 4, files.getY() + 6, 0xFFFF6060, true);
        else if (files.children().isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable("blockcompanion.library.empty"), files.getWidth() - 12);
            for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), files.getX() + 6, files.getY() + 6 + i * 10, 0xFFB0B0B0, true);
        }
        if (count == 0) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal("Nothing loaded. Pick a schematic and press Load; load as many as you like."), loaded.getWidth() - 12);
            for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), loaded.getX() + 6, loaded.getY() + 6 + i * 10, 0xFFB0B0B0, true);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A line about a project: its layers. */
    private String describe(SchematicLibrary.Entry e) {
        if (!e.isProject()) return e.preferred() ? null : e.kind().label() + " file: loaded for compatibility, saves are .schem";
        String key;
        try {
            key = e.name() + "@" + Files.getLastModifiedTime(e.path()).toMillis();
        } catch (IOException ex) {
            key = e.name();
        }
        return projectInfo.computeIfAbsent(key, k -> {
            try {
                BdProject.Info info = BdProject.readInfo(e.path());
                StringBuilder b = new StringBuilder("Layers: ");
                int hidden = 0;
                for (int i = 0; i < info.layers().size(); i++) {
                    BdProject.LayerInfo l = info.layers().get(i);
                    if (i > 0) b.append(", ");
                    b.append(l.name());
                    if (!l.visible()) {
                        b.append(" (hidden)");
                        hidden++;
                    }
                }
                if (info.layers().isEmpty()) b.append("none");
                if (hidden > 0) b.append("; hidden layers are not placed");
                return b.toString();
            } catch (IOException | RuntimeException ex) {
                return "Can't read this project: " + ex.getMessage();
            }
        });
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---- the library list -------------------------------------------------------------------------------------------

    private final class FileList extends ObjectSelectionList<FileEntry> {
        FileList(Minecraft mc, int w, int h, int y) {
            super(mc, w, h, y, 14);
        }

        void set(List<SchematicLibrary.Entry> entries) {
            clearEntries();
            for (SchematicLibrary.Entry e : entries) addEntry(new FileEntry(e));
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        @Override
        protected int scrollBarX() {
            return getX() + width - 6;
        }

        @Override
        public void setSelected(FileEntry e) {
            super.setSelected(e);
            updateButtons();
        }
    }

    private final class FileEntry extends ObjectSelectionList.Entry<FileEntry> {
        final SchematicLibrary.Entry entry;

        FileEntry(SchematicLibrary.Entry entry) {
            this.entry = entry;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = getContentY(), left = getContentX(), w = getContentWidth();
            long copies = BlockCompanionClient.placements().stream().filter(lp -> lp.name().equals(entry.name())).count();
            int color = copies > 0 ? 0xFFFFE066 : entry.preferred() ? 0xFFFFFFFF : 0xFFB8B8B8;
            String size = size(entry.size());
            String kind = entry.kind().label();
            int right = left + w - 2;
            g.text(font, size, right - font.width(size), top + 2, 0xFF8A8A8A, false);
            int kx = right - 52 - font.width(kind);
            g.text(font, kind, kx, top + 2, entry.preferred() ? 0xFF7CD67C : 0xFF8A8A8A, false);
            String name = entry.name() + (copies > 1 ? "  ×" + copies : "");
            g.text(font, font.plainSubstrByWidth(name, kx - left - 8), left + 2, top + 2, color, false);
            if (hovering) {
                String info = describe(entry);
                if (info != null) g.setTooltipForNextFrame(font, font.split(Component.literal(info), 240), mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            files.setSelected(this);
            if (doubleClick) loadSelected();
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.name());
        }
    }

    // ---- the loaded list --------------------------------------------------------------------------------------------

    private final class LoadedList extends ObjectSelectionList<LoadedEntry> {
        LoadedList(Minecraft mc, int w, int h, int y) {
            super(mc, w, h, y, 26);
        }

        void set(List<LoadedPlacement> list) {
            LoadedPlacement keep = selectedPlacement() != null ? selectedPlacement() : BlockCompanionClient.active();
            clearEntries();
            for (LoadedPlacement lp : list) {
                LoadedEntry e = new LoadedEntry(lp);
                addEntry(e);
                if (lp == keep) super.setSelected(e);
            }
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        @Override
        protected int scrollBarX() {
            return getX() + width - 6;
        }

        @Override
        public void setSelected(LoadedEntry e) {
            super.setSelected(e);
            if (e != null) BlockCompanionClient.setActive(e.lp);
            updateButtons();
        }
    }

    private final class LoadedEntry extends ObjectSelectionList.Entry<LoadedEntry> {
        final LoadedPlacement lp;

        LoadedEntry(LoadedPlacement lp) {
            this.lp = lp;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int top = getContentY(), left = getContentX(), w = getContentWidth();
            boolean active = lp == BlockCompanionClient.active();
            int right = left + w - 2;
            ProgressTracker t = lp.progress().tracker();
            String pct = t == null ? "" : String.format(Locale.ROOT, "%.0f%%", Math.floor(t.totals().fraction() * 100));
            g.text(font, pct, right - font.width(pct), top + 2, 0xFFFFFFFF, false);
            StringBuilder tags = new StringBuilder();
            if (!lp.visible) tags.append("hidden ");
            if (!lp.locks.isEmpty()) tags.append(lp.locks.containsAll(PlacementLock.IN_PLACE) ? "locked " : "part-locked ");
            if (lp.live) tags.append("live ");
            String tagText = tags.toString().trim();
            int tx = right - font.width(pct) - 6 - font.width(tagText);
            g.text(font, tagText, tx, top + 2, 0xFF8FC7FF, false);
            g.text(font, font.plainSubstrByWidth(lp.shortName(), tx - left - 8), left + 2, top + 2, active ? 0xFFFFE066 : 0xFFFFFFFF, false);
            String where = dimension(lp.dimension) + "  " + lp.placement.origin().x() + ", " + lp.placement.origin().y() + ", "
                    + lp.placement.origin().z() + (lp.placement.rotation() != 0 ? "  " + lp.placement.rotation() * 90 + "°" : "")
                    + (lp.placement.mirrored() ? "  mirrored" : "");
            g.text(font, font.plainSubstrByWidth(where, w - 70), left + 2, top + 13, 0xFF8A8A8A, false);
            if (t != null) {
                double f = t.totals().fraction();
                if (f >= 1) Bars.solid(g, right - 60, top + 14, 60, 1, 0xFFFFD75A);
                else Bars.gradient(g, right - 60, top + 14, 60, f);
            }
            if (hovering) {
                g.setTooltipForNextFrame(font, Component.literal(lp.name() + "\n" + PlacementLock.describe(lp.locks)
                        + (lp.fromBlockDesigner() ? "\nFrom BlockDesigner" + (lp.live ? ", follows its changes" : ", not following") : "")), mouseX, mouseY);
            }
        }

        private String dimension(String id) {
            String p = id.substring(id.indexOf(':') + 1).replace('_', ' ');
            return p.isEmpty() ? id : Character.toUpperCase(p.charAt(0)) + p.substring(1);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            loaded.setSelected(this);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(lp.shortName()).withStyle(ChatFormatting.WHITE);
        }
    }
}
