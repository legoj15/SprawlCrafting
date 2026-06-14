package com.legoj15.sprawlcrafting.compat.jei;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.MissingIngredientsScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Discovered by JEI itself on both loaders — the {@code @JeiPlugin} annotation scan on
 * NeoForge, the {@code jei_mod_plugin} entrypoint (fabric.mod.json) on Fabric — so this
 * class never loads when JEI is absent.
 *
 * <p>Scope note: only the crafting table screen's handler is replaced. The player 2×2
 * uses JEI-internal index-trimming logic that isn't API-visible; that grid is already
 * covered by the recipe book integration.
 */
@JeiPlugin
public class SprawlCraftingJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        IRecipeTransferHandlerHelper helper = registration.getTransferHelper();
        // Same slot layout JEI's own VanillaPlugin registers for the crafting table:
        // grid slots 1-9, player inventory 10-45.
        IRecipeTransferInfo<CraftingMenu, RecipeHolder<CraftingRecipe>> tableInfo =
                helper.createBasicRecipeTransferInfo(CraftingMenu.class, MenuType.CRAFTING,
                        RecipeTypes.CRAFTING, 1, 9, 10, 36);
        registration.addRecipeTransferHandler(
                new DeferredCraftingTransferHandler(helper.createUnregisteredRecipeTransferHandler(tableInfo)),
                RecipeTypes.CRAFTING);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Two halves of making R/U work on the gather-list screen (a plain Screen, not a container):
        // the screen handler marks it as JEI-handled (R/U input is gated on getGuiProperties), and the
        // global handler reports the hovered item as the ingredient under the mouse.
        registration.addGuiScreenHandler(MissingIngredientsScreen.class, GatherListGuiHandler::guiProperties);
        registration.addGlobalGuiHandler(new GatherListGuiHandler());
    }
}
