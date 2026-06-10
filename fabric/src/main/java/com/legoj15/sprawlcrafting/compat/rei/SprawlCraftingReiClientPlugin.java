package com.legoj15.sprawlcrafting.compat.rei;

import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;

/**
 * Fabric REI client plugin, discovered via the {@code rei_client} entrypoint in
 * fabric.mod.json (Fabric needs no annotation, unlike NeoForge). Loads only when REI is
 * present. Behavior matches the NeoForge plugin.
 */
public class SprawlCraftingReiClientPlugin implements REIClientPlugin {

    @Override
    public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(new DeferredCraftingReiTransferHandler());
    }
}
