package com.legoj15.sprawlcrafting.compat.jei;

import java.util.Optional;

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
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Replaces JEI's builtin transfer handler for the crafting table screen. Direct
 * transfers (all ingredients present) delegate to JEI's own logic untouched; when JEI
 * would show the red "Missing items" error but the recipe is solvable from raw
 * resources, the transfer button turns yellow and inviting instead — clicking it sends
 * the same start packet the recipe book uses ({@link DeferredTransfers} makes that call,
 * shared with the Sophisticated crafting-upgrade handlers).
 */
// JEI's recipe type R is RecipeHolder<CraftingRecipe> on 1.20.2+, but the bare CraftingRecipe on
// 1.20.1 (JEI 15); the 1.20.1 branch wraps it in the RecipeHolder shim right after the JEI call.
//? if >=1.20.2 {
public class DeferredCraftingTransferHandler
        implements IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> {
//?} else {
/*public class DeferredCraftingTransferHandler
        implements IRecipeTransferHandler<CraftingMenu, CraftingRecipe> {*/
//?}

    //? if >=1.20.2 {
    private final IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> vanilla;

    public DeferredCraftingTransferHandler(
            IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> vanilla) {
        this.vanilla = vanilla;
    }
    //?} else {
    /*private final IRecipeTransferHandler<CraftingMenu, CraftingRecipe> vanilla;

    public DeferredCraftingTransferHandler(IRecipeTransferHandler<CraftingMenu, CraftingRecipe> vanilla) {
        this.vanilla = vanilla;
    }*/
    //?}

    @Override
    public Class<? extends CraftingMenu> getContainerClass() {
        return CraftingMenu.class;
    }

    @Override
    public Optional<MenuType<CraftingMenu>> getMenuType() {
        return Optional.of(MenuType.CRAFTING);
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
    public IRecipeTransferError transferRecipe(CraftingMenu container, RecipeHolder<CraftingRecipe> recipe,
    //?} else {
    /*public IRecipeTransferError transferRecipe(CraftingMenu container, CraftingRecipe recipe,*/
    //?}
                                               IRecipeSlotsView recipeSlots, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        IRecipeTransferError vanillaError =
                vanilla.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        //? if >=1.20.2 {
        RecipeHolder<CraftingRecipe> holder = recipe;
        //?} else {
        /*RecipeHolder<CraftingRecipe> holder = RecipeHolder.of(recipe);*/
        //?}
        return DeferredTransfers.resolve(vanillaError, holder, doTransfer);
    }
}
