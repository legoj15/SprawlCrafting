package com.legoj15.sprawlcrafting.forge.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.VanillaRecipeCategoryUid;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferRegistry;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ContainerWorkbench;

/**
 * JEI integration entry point. Discovered by JEI's {@code @JEIPlugin} scan (client-only); never
 * loaded when JEI is absent, so JEI stays a soft, compile-only dependency. Registers the deferred
 * craft transfer handler for the 2x2 inventory grid and the 3x3 table on the crafting category.
 */
@JEIPlugin
public class SprawlCraftingJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        IRecipeTransferHandlerHelper helper = registry.getJeiHelpers().recipeTransferHandlerHelper();
        IRecipeTransferRegistry transfer = registry.getRecipeTransferRegistry();
        transfer.addRecipeTransferHandler(
                new DeferredCraftTransferHandler<ContainerWorkbench>(ContainerWorkbench.class, helper),
                VanillaRecipeCategoryUid.CRAFTING);
        transfer.addRecipeTransferHandler(
                new DeferredCraftTransferHandler<ContainerPlayer>(ContainerPlayer.class, helper),
                VanillaRecipeCategoryUid.CRAFTING);
    }
}
