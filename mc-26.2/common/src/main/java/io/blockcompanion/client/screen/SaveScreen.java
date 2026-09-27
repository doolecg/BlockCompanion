package io.blockcompanion.client.screen;

import io.blockcompanion.client.BlockCompanionClient;
import io.blockcompanion.client.RegionSaver;
import io.blockcompanion.core.library.SchematicLibrary;
import io.blockcompanion.core.model.Box;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.IOException;
import java.util.Locale;

/**
 * Saves a box of the world as a Sponge schematic ({@code .schem}) in the library: the two marked corners, or else the
 * loaded schematic's box. Asks for a name; a second click on Save replaces an existing file.
 */
public final class SaveScreen extends Screen {
    private final Screen parent;
    private EditBox name;
    private Button save;
    private BlockCompanionClient.SaveRegion region;
    private String status;
    private boolean statusError;
    /** The file name the player already agreed to replace. */
    private String confirmed;

    public SaveScreen(Screen parent) {
        super(Component.translatable("blockcompanion.save.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        region = BlockCompanionClient.saveRegion();
        int cx = width / 2, y = height / 2 - 30;
        String previous = name == null ? (region == null ? "" : region.suggestedName()) : name.getValue();
        name = new EditBox(font, cx - 110, y, 220, 20, Component.translatable("blockcompanion.save.name"));
        name.setMaxLength(80);
        name.setValue(previous);
        name.setResponder(s -> updateStatus());
        addRenderableWidget(name);
        setInitialFocus(name);

        int w = 72, gap = 4, bx = cx - (3 * w + 2 * gap) / 2, by = y + 52;
        save = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.save.save"), b -> doSave()).bounds(bx, by, w, 20).build());
        Button clear = addRenderableWidget(Button.builder(Component.translatable("blockcompanion.save.clear"), b -> {
            BlockCompanionClient.selection().clear();
            rebuildWidgets();
        }).bounds(bx + w + gap, by, w, 20).build());
        clear.active = !BlockCompanionClient.selection().isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(bx + 2 * (w + gap), by, w, 20).build());
        updateStatus();
    }

    private void updateStatus() {
        statusError = false;
        status = null;
        if (region == null) {
            status = "Mark two corners with " + BlockCompanionClient.keyName("select_corner") + " first, or load a schematic";
            statusError = true;
        } else {
            String file = SchematicLibrary.saveFileName(name.getValue());
            if (file == null) {
                status = "Type a name";
                statusError = true;
            } else {
                try {
                    if (BlockCompanionClient.library().saveExists(name.getValue())) {
                        status = file.equals(confirmed) ? "Save again to replace " + file : file + " already exists: Save twice to replace it";
                        statusError = true;
                    } else {
                        status = "Saves " + file + " in the schematic folder";
                    }
                } catch (IOException e) {
                    status = e.getMessage();
                    statusError = true;
                }
            }
        }
        if (save != null) save.active = region != null && SchematicLibrary.saveFileName(name.getValue()) != null;
    }

    private void doSave() {
        if (region == null) return;
        String typed = name.getValue();
        String file = SchematicLibrary.saveFileName(typed);
        if (file == null) return;
        boolean exists;
        try {
            exists = BlockCompanionClient.library().saveExists(typed);
        } catch (IOException e) {
            status = e.getMessage();
            statusError = true;
            return;
        }
        if (exists && !file.equals(confirmed)) {
            confirmed = file;
            updateStatus();
            return;
        }
        RegionSaver.save(region.box(), typed, exists);
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            if (save.active) doSave();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int cx = width / 2, y = height / 2 - 30;
        Ui.panel(g, cx - 124, y - 48, 248, 128);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = width / 2, y = height / 2 - 30;
        Ui.section(g, font, title.getString(), cx - 116, y - 40, 232);
        if (region != null) {
            Box b = region.box();
            String what = String.format(Locale.ROOT, "%d × %d × %d at %d, %d, %d (%s)", b.sizeX(), b.sizeY(), b.sizeZ(),
                    b.minX(), b.minY(), b.minZ(), region.source());
            g.centeredText(font, what, cx, y - 24, Ui.MUTED);
        }
        g.text(font, Component.translatable("blockcompanion.save.name"), cx - 110, y - 11, 0xFFA0A0A0, false);
        if (status != null) g.centeredText(font, status, cx, y + 28, statusError ? Ui.BAD : Ui.GOOD);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
