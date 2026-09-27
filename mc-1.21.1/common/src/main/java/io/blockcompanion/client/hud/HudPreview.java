package io.blockcompanion.client.hud;

import io.blockcompanion.client.LoadedPlacement;
import io.blockcompanion.core.hud.HudLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Draws a HUD piece for the HUD editor: with the real placement when one is loaded, else with sample content. */
public final class HudPreview {
    private HudPreview() {
    }

    /** Draws {@code e} at {@code at}; returns where it went {x, y, w, h}. */
    public static int[] draw(GuiGraphics g, HudLayout.Element e, HudLayout.Placement at, LoadedPlacement lp) {
        Minecraft mc = Minecraft.getInstance();
        return switch (e) {
            case PANEL -> Hud.drawPanel(g, mc.font, at, lp == null || lp.progress().tracker() == null ? Hud.sampleRows() : Hud.rows(mc, lp));
            case HINT -> Hud.drawHint(g, mc.font, at, "Should be Oak Stairs · facing north", 0xFFFF8A80);
            case TOOL -> Hud.drawPanel(g, mc.font, at, Hud.toolRows(io.blockcompanion.client.BlockCompanionClient.config()));
        };
    }
}
