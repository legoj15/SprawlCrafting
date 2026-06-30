package com.legoj15.sprawlcrafting.forge.compat.jei;

import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.VanillaRecipeCategoryUid;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferRegistry;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraftforge.fml.common.Loader;

/**
 * JEI integration entry point. Discovered by JEI's {@code @JEIPlugin} scan (client-only); never
 * loaded when JEI is absent, so JEI stays a soft, compile-only dependency. Registers the deferred
 * craft transfer handler for the 2x2 inventory grid, the 3x3 table, and any modded crafting
 * containers detected at runtime (FastWorkbench, TConstruct Crafting Station).
 */
@JEIPlugin
public class SprawlCraftingJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        IRecipeTransferHandlerHelper helper = registry.getJeiHelpers().recipeTransferHandlerHelper();
        IRecipeTransferRegistry transfer = registry.getRecipeTransferRegistry();

        // Vanilla containers.
        transfer.addRecipeTransferHandler(
                new DeferredCraftTransferHandler<ContainerWorkbench>(ContainerWorkbench.class, helper),
                VanillaRecipeCategoryUid.CRAFTING);
        transfer.addRecipeTransferHandler(
                new DeferredCraftTransferHandler<ContainerPlayer>(ContainerPlayer.class, helper),
                VanillaRecipeCategoryUid.CRAFTING);

        // FastWorkbench: extends ContainerWorkbench, so grid context auto-detects as 3x3.
        registerModdedCrafter(transfer, helper, "fastbench",
                "shadows.fastbench.gui.ContainerFastBench", null);

        // TConstruct Crafting Station: does NOT extend ContainerWorkbench, needs explicit 3x3.
        registerModdedCrafter(transfer, helper, "tconstruct",
                "slimeknights.tconstruct.tools.common.inventory.ContainerCraftingStation",
                GridContext.CRAFTING_TABLE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerModdedCrafter(IRecipeTransferRegistry transfer,
            IRecipeTransferHandlerHelper helper, String modId, String containerClassName,
            GridContext gridOverride) {
        if (!Loader.isModLoaded(modId)) {
            return;
        }
        try {
            Class cls = Class.forName(containerClassName);
            transfer.addRecipeTransferHandler(
                    new DeferredCraftTransferHandler(cls, helper, gridOverride),
                    VanillaRecipeCategoryUid.CRAFTING);
        } catch (ClassNotFoundException ignored) {
            // Mod's internal layout changed; silently skip.
        }
    }
}
