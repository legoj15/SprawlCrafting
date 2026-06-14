package com.legoj15.sprawlcrafting.platform.neoforge;

import com.legoj15.sprawlcrafting.client.MissingIngredientsScreen;
import com.legoj15.sprawlcrafting.compat.rei.DeferredCraftingReiTransferHandler;
import com.legoj15.sprawlcrafting.compat.rei.ReiRecipeLookup;

import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.forge.REIPluginClient;

/**
 * NeoForge REI client plugin. REI on NeoForge discovers plugins by scanning for the
 * {@code @REIPluginClient} annotation (loader-specific, not in the common REI API), so the
 * plugin lives here rather than in shared code. Loads only when REI is present.
 */
@REIPluginClient
public class NeoForgeReiPlugin implements REIClientPlugin {

    @Override
    public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(new DeferredCraftingReiTransferHandler());
    }

    @Override
    public void registerScreens(ScreenRegistry registry) {
        // REI's key handler doesn't run on our plain gather screen, so the screen forwards R/U here.
        MissingIngredientsScreen.setRecipeLookup(new ReiRecipeLookup());
    }
}
