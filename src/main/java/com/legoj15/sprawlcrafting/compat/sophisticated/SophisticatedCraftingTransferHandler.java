package com.legoj15.sprawlcrafting.compat.sophisticated;

import java.util.Optional;

import com.legoj15.sprawlcrafting.compat.jei.DeferredTransfers;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
//? if >=1.21.11 {
/*import mezz.jei.api.recipe.types.IRecipeType;*/
//?} else {
import mezz.jei.api.recipe.RecipeType;
//?}
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

/**
 * Fronts a Sophisticated crafting-upgrade JEI transfer handler, the way {@code DeferredCraftingTransferHandler}
 * fronts JEI's own crafting-table handler: the direct transfer is the mod's, untouched (it fills the upgrade's
 * grid through the mod's own packet); when it reports a user-facing "missing items" error, the button becomes
 * the yellow deferred craft or the orange gather list ({@link DeferredTransfers}). A menu with no crafting
 * upgrade gets the mod's internal error back, which is passed through, so no offer appears there.
 */
// JEI's recipe type R is RecipeHolder<CraftingRecipe> on 1.20.2+, but the bare CraftingRecipe on 1.20.1 (JEI 15,
// where Core's handler base takes the same); the 1.20.1 branch wraps it in the RecipeHolder shim for DeferredTransfers.
//? if >=1.20.2 {
final class SophisticatedCraftingTransferHandler<C extends StorageContainerMenuBase<?>>
        implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {
//?} else {
/*final class SophisticatedCraftingTransferHandler<C extends StorageContainerMenuBase<?>>
        implements IRecipeTransferHandler<C, CraftingRecipe> {*/
//?}

    //? if >=1.20.2 {
    private final IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> sophisticated;

    SophisticatedCraftingTransferHandler(IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> sophisticated) {
        this.sophisticated = sophisticated;
    }
    //?} else {
    /*private final IRecipeTransferHandler<C, CraftingRecipe> sophisticated;

    SophisticatedCraftingTransferHandler(IRecipeTransferHandler<C, CraftingRecipe> sophisticated) {
        this.sophisticated = sophisticated;
    }*/
    //?}

    @Override
    public Class<? extends C> getContainerClass() {
        return sophisticated.getContainerClass();
    }

    @Override
    public Optional<MenuType<C>> getMenuType() {
        return sophisticated.getMenuType();
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

    @Override
    //? if >=1.20.2 {
    public IRecipeTransferError transferRecipe(C container, RecipeHolder<CraftingRecipe> recipe,
    //?} else {
    /*public IRecipeTransferError transferRecipe(C container, CraftingRecipe recipe,*/
    //?}
                                               IRecipeSlotsView recipeSlots, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        IRecipeTransferError inner =
                sophisticated.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        //? if >=1.20.2 {
        RecipeHolder<CraftingRecipe> holder = recipe;
        //?} else {
        /*RecipeHolder<CraftingRecipe> holder = RecipeHolder.of(recipe);*/
        //?}
        return DeferredTransfers.resolve(inner, holder, doTransfer);
    }
}
