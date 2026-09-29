package io.blockcompanion.mixin;

import io.blockcompanion.client.Tutorial;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "BlockCompanion tutorial" button to the title screen, right under the Realms button. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    private TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void blockcompanion$addTutorialButton(CallbackInfo ci) {
        // Find the Realms button (its text is the "menu.online" translation).
        AbstractWidget realms = null;
        for (GuiEventListener child : children()) {
            if (child instanceof AbstractWidget widget && widget.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals("menu.online")) {
                realms = widget;
            }
        }

        // Without a Realms button (a demo game, say), the button goes in the top left corner instead.
        int x = 6, y = 6, width = 130;
        if (realms != null) {
            x = this.width / 2 - 100;
            y = realms.getY() + 24;
            width = 200;
            // Everything under the Realms button (Options, Quit and the small icon buttons) moves down one row to make room.
            for (GuiEventListener child : children()) {
                if (child instanceof AbstractWidget widget && widget.getY() >= y) widget.setY(widget.getY() + 24);
            }
        }

        // Opens the tutorial world (made the first time), where a step-by-step tutorial starts.
        addRenderableWidget(Button.builder(Component.literal("BlockCompanion tutorial"), b -> Tutorial.open(this))
                .bounds(x, y, width, 20)
                .tooltip(Tooltip.create(Component.literal("Learn BlockCompanion step by step, in a world made for it.")))
                .build());
    }
}
