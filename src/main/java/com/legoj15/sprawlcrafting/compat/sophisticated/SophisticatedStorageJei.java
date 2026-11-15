package com.legoj15.sprawlcrafting.compat.sophisticated;

import mezz.jei.api.constants.RecipeTypes;
//? if >=1.21.11 {
/*import mezz.jei.api.recipe.types.IRecipeType;*/
//?} else {
import mezz.jei.api.recipe.RecipeType;
//?}
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.jei.JeiCraftingContainerRecipeTransferHandlerBase;
import net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu;

/** The crafting-upgrade JEI handler for Sophisticated Storage's block screen. Load only with that mod present. */
final class SophisticatedStorageJei {

    private SophisticatedStorageJei() {
    }

    static void register(IRecipeTransferRegistration registration) {
        // Built exactly as StorageJeiPlugin builds its own, so a direct transfer is byte-for-byte what the mod does.
        //? if >=1.20.2 {
        JeiCraftingContainerRecipeTransferHandlerBase<StorageContainerMenu, RecipeHolder<CraftingRecipe>> storage =
                new JeiCraftingContainerRecipeTransferHandlerBase<StorageContainerMenu, RecipeHolder<CraftingRecipe>>(
        //?} else {
        /*JeiCraftingContainerRecipeTransferHandlerBase<StorageContainerMenu, CraftingRecipe> storage =
                new JeiCraftingContainerRecipeTransferHandlerBase<StorageContainerMenu, CraftingRecipe>(*/
        //?}
                        registration.getTransferHelper(), registration.getJeiHelpers().getStackHelper()) {
                    @Override
                    public Class<StorageContainerMenu> getContainerClass() {
                        return StorageContainerMenu.class;
                    }

                    @Override
                    //? if >=1.21.11 {
                    /*public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {*/
                    //?} else if >=1.20.2 {
                    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
                    //?} else {
                    /*public RecipeType<CraftingRecipe> getRecipeType() {*/
                    //?}
                        return RecipeTypes.CRAFTING;
                    }
                };
        registration.addRecipeTransferHandler(new SophisticatedCraftingTransferHandler<>(storage), RecipeTypes.CRAFTING);
    }
}
