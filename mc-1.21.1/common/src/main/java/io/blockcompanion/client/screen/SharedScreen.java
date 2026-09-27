package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.core.sync.Features;
import io.blockcompanion.core.sync.Permission;
import io.blockcompanion.core.sync.SchematicInfo;
import io.blockcompanion.core.sync.SharedPlacement;
import io.blockcompanion.core.sync.SyncClient;
import io.blockcompanion.network.ClientSync;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The server's shared space (milestone 2): shared placements and schematics, with buttons to share the loaded
 * schematic, load a placement (downloading its file if needed), lock it and delete things. Opened with the
 * shared-space key.
 */
public final class SharedScreen extends Screen {
    private static final int ROW = 14;
    private final Screen parent;
    /** Rows: a header (null entry), a placement or a schematic. */
    private final List<Object> rows = new ArrayList<>();
    private Object selected;
    private double scroll;
    private int listTop, listBottom, listLeft, listRight;
    private Button share, load, lock, delete, unlink;

    public SharedScreen(Screen parent) {
        super(Component.translatable("blockcompanion.shared.title"));
        this.parent = parent;
    }

    private static SyncClient sync() {
        return ClientSync.client();
    }

    @Override
    protected void init() {
        listLeft = width / 2 - 180;
        listRight = width / 2 + 180;
        listTop = 48;
        listBottom = height - 36;
        int y = height - 28, w = 66, gap = 4, x = width / 2 - (6 * w + 5 * gap) / 2;
        share = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.shared.share"), b -> sync().shareCurrent())
                .bounds(x, y, w, 20).build());
        load = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.shared.load"), b -> loadSelected())
                .bounds(x + (w + gap), y, w, 20).build());
        lock = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.shared.lock"), b -> {
            if (selected instanceof SharedPlacement p) sync().setLocked(p.id(), !p.locked());
        }).bounds(x + 2 * (w + gap), y, w, 20).build());
        delete = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.shared.delete"), b -> {
            if (selected instanceof SharedPlacement p) sync().deletePlacement(p.id());
            else if (selected instanceof SchematicInfo s) sync().deleteSchematic(s.hash());
            selected = null;
        }).bounds(x + 3 * (w + gap), y, w, 20).build());
        unlink = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.shared.unlink"), b -> {
            sync().releaseEditLock();
            sync().unlink();
            refresh();
        }).bounds(x + 4 * (w + gap), y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 5 * (w + gap), y, w, 20).build());
        refresh();
    }

    /** Rebuilds the rows from the sync client (called when anything changes). */
    public void refresh() {
        rows.clear();
        SyncClient s = sync();
        if (s == null) return;
        List<SharedPlacement> placements = new ArrayList<>(s.placements());
        placements.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        rows.add("placements");
        rows.addAll(placements);
        rows.add("schematics");
        rows.addAll(s.schematics());
        // Keep the selection if it still exists (by id or hash, since records are replaced on every update).
        Object keep = null;
        for (Object r : rows) {
            if (r instanceof SharedPlacement p && selected instanceof SharedPlacement q && p.id().equals(q.id())) keep = r;
            if (r instanceof SchematicInfo i && selected instanceof SchematicInfo j && i.hash().equals(j.hash())) keep = r;
        }
        selected = keep;
        updateButtons();
    }

    private void updateButtons() {
        if (share == null) return;
        SyncClient s = sync();
        Features f = s == null ? Features.NONE : s.features();
        boolean on = s != null && s.serverPresent() && f.syncEnabled();
        boolean admin = f.can(Permission.ADMIN);
        share.active = on && BlockCompanionClient.placement() != null && (f.can(Permission.PLACE) || admin);
        load.active = on && selected != null;
        lock.active = on && selected instanceof SharedPlacement p && (admin || (p.owner().equals(s.self()) && f.can(Permission.LOCK)));
        lock.setMessage(Component.translatable(selected instanceof SharedPlacement p && p.locked()
                ? "blockcompanion.shared.unlock" : "blockcompanion.shared.lock"));
        delete.active = on && (selected instanceof SharedPlacement p ? !p.locked() || admin || p.owner().equals(s.self())
                : selected instanceof SchematicInfo i && (admin || i.uploader().equals(s.self())));
        unlink.active = on && s.linked() != null;
    }

    private void loadSelected() {
        SyncClient s = sync();
        if (selected instanceof SharedPlacement p) {
            s.load(p.id());
            onClose();
        } else if (selected instanceof SchematicInfo i) {
            // A plain load: fetch the file into the library and put it in front of the player, not linked to anything.
            s.fetch(i.hash(), BlockCompanionClient::load);
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        SyncClient s = sync();
        g.drawCenteredString(font, status(s), width / 2, 22, 0xFF9A9A9A);
        List<String> activity = s == null ? List.of() : s.activity();
        if (!activity.isEmpty()) g.drawCenteredString(font, String.join("   ", activity), width / 2, 34, 0xFFFFE066);
        g.fill(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2, 0x60000000);
        if (s == null || !s.serverPresent() || !s.features().syncEnabled()) return;
        g.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = (int) (scroll / ROW); i < rows.size(); i++) {
            int y = listTop + i * ROW - (int) scroll;
            if (y > listBottom) break;
            Object row = rows.get(i);
            boolean hot = !(row instanceof String) && mouseX >= listLeft && mouseX < listRight && mouseY >= y && mouseY < y + ROW
                    && mouseY >= listTop && mouseY < listBottom;
            if (Objects.equals(row, selected)) g.fill(listLeft, y, listRight, y + ROW, 0x60FFFFFF);
            else if (hot) g.fill(listLeft, y, listRight, y + ROW, 0x30FFFFFF);
            drawRow(g, s, row, y);
        }
        g.disableScissor();
    }

    private void drawRow(GuiGraphics g, SyncClient s, Object row, int y) {
        int w = listRight - listLeft;
        if (row instanceof String header) {
            String text = Component.translatable("blockcompanion.shared." + header).getString();
            g.drawString(font, text, listLeft + 4, y + 3, 0xFF7FD0FF, false);
        } else if (row instanceof SharedPlacement p) {
            String state = p.editor() != null ? p.editorName() + " moving" : p.locked() ? "locked" : "";
            String right = p.ownerName() + "  " + p.x() + " " + p.y() + " " + p.z() + (state.isEmpty() ? "" : "  [" + state + "]");
            boolean linked = p.id().equals(s.linked());
            int color = linked ? 0xFFFFE066 : p.locked() ? 0xFFFFB070 : 0xFFFFFFFF;
            g.drawString(font, font.plainSubstrByWidth((linked ? "> " : "  ") + p.name(), w - font.width(right) - 16), listLeft + 4, y + 3, color, false);
            g.drawString(font, right, listRight - 4 - font.width(right), y + 3, 0xFF9A9A9A, false);
        } else if (row instanceof SchematicInfo i) {
            String right = i.uploaderName() + "  " + size(i.size());
            g.drawString(font, font.plainSubstrByWidth("  " + i.name(), w - font.width(right) - 16), listLeft + 4, y + 3, 0xFFFFFFFF, false);
            g.drawString(font, right, listRight - 4 - font.width(right), y + 3, 0xFF9A9A9A, false);
        }
    }

    private static String status(SyncClient s) {
        if (s == null || !s.serverPresent()) return Component.translatable("blockcompanion.shared.no_server").getString();
        Features f = s.features();
        if (!f.syncEnabled()) return Component.translatable("blockcompanion.shared.disabled").getString();
        String quota = f.playerQuota() < 0 ? "no quota" : size(f.playerUsed()) + " of " + size(f.playerQuota()) + " used";
        return s.serverSoftware() + "  -  " + quota + ", files up to " + size(f.maxFileSize());
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listLeft && mouseX < listRight && mouseY >= listTop && mouseY < listBottom) {
            int i = (int) ((mouseY - listTop + scroll) / ROW);
            if (i >= 0 && i < rows.size() && !(rows.get(i) instanceof String)) {
                selected = rows.get(i);
                updateButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, rows.size() * ROW - (listBottom - listTop));
        scroll = Math.max(0, Math.min(max, scroll - scrollY * ROW * 3));
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
