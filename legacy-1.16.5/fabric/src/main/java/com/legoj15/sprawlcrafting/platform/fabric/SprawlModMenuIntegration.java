package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.client.SprawlConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu "Config" button → {@link SprawlConfigScreen}. Discovered via the {@code modmenu} entrypoint
 * in fabric.mod.json; Mod Menu is optional (compile-only), so this class only loads when it is present.
 */
public class SprawlModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SprawlConfigScreen::new;
    }
}
