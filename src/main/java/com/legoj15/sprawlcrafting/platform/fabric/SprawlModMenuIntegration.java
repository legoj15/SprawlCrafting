package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.client.SprawlConfigScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Fabric Mod Menu integration: wires the mod's "Config" button to {@link SprawlConfigScreen}.
 * Discovered via the {@code modmenu} entrypoint in fabric.mod.json. Loads only when Mod Menu is
 * present, so it is excluded from compilation on nodes that don't pin a {@code modmenu_version} in
 * their per-node gradle.properties (the same mechanism that gates REI off the nodes that lack it).
 * Both 1.21.1 (Mod Menu 11) and the 26.1.x line (Mod Menu 18) pin a version, so it compiles on both.
 */
public class SprawlModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SprawlConfigScreen::new;
    }
}
