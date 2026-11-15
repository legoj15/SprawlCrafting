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
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.jei.JeiCraftingContainerRecipeTransferHandlerBase;

/**
 * The crafting-upgrade JEI handler for Sophisticated Backpacks' backpack screen. Load only with that mod present.
 * (The backpack's smithing-upgrade handler is left to the mod: nothing of ours applies to smithing.)
 */
final class SophisticatedBackpacksJei {

    private SophisticatedBackpacksJei() {
    }

    static void register(IRecipeTransferRegistration registration) {
        // Built exactly as BackpackJeiPlugin builds its own, so a direct transfer is byte-for-byte what the mod does.
        //? if >=1.20.2 {
        JeiCraftingContainerRecipeTransferHandlerBase<BackpackContainer, RecipeHolder<CraftingRecipe>> backpack =
                new JeiCraftingContainerRecipeTransferHandlerBase<BackpackContainer, RecipeHolder<CraftingRecipe>>(
        //?} else {
        /*JeiCraftingContainerRecipeTransferHandlerBase<BackpackContainer, CraftingRecipe> backpack =
                new JeiCraftingContainerRecipeTransferHandlerBase<BackpackContainer, CraftingRecipe>(*/
        //?}
                        registration.getTransferHelper(), registration.getJeiHelpers().getStackHelper()) {
                    @Override
                    public Class<BackpackContainer> getContainerClass() {
                        return BackpackContainer.class;
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
        registration.addRecipeTransferHandler(new SophisticatedCraftingTransferHandler<>(backpack), RecipeTypes.CRAFTING);
    }
}
