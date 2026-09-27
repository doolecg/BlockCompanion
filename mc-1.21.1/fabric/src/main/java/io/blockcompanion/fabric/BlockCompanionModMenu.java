package io.blockcompanion.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.blockcompanion.client.screen.SettingsScreen;

/** Mod Menu's settings button opens BlockCompanion's settings screen. */
public final class BlockCompanionModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
