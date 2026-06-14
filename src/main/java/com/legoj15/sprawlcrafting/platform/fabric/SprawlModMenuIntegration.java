package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.client.SprawlConfigScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Fabric Mod Menu integration: wires the mod's "Config" button to {@link SprawlConfigScreen}.
 * Discovered via the {@code modmenu} entrypoint in fabric.mod.json. Loads only when Mod Menu is
 * present, so it is excluded from compilation on nodes without a Mod Menu build (26.x, where no
 * release exists yet — gated on {@code modmenu_version} in the per-node gradle.properties, the
 * same mechanism that gates REI off the nodes that lack it).
 */
public class SprawlModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SprawlConfigScreen::new;
    }
}
