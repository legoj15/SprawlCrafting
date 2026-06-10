package com.legoj15.sprawlcrafting.compat.jei;

import java.util.Optional;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.craft.GridContext;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
 * the same start packet the recipe book uses.
 */
public class DeferredCraftingTransferHandler
        implements IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> {

    private static final IRecipeTransferError DEFERRED_AVAILABLE = new DeferredCraftError();

    private final IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> vanilla;

    public DeferredCraftingTransferHandler(
            IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> vanilla) {
        this.vanilla = vanilla;
    }

    @Override
    public Class<? extends CraftingMenu> getContainerClass() {
        return CraftingMenu.class;
    }

    @Override
    public Optional<MenuType<CraftingMenu>> getMenuType() {
        return Optional.of(MenuType.CRAFTING);
    }

    @Override
    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    @Override
    public IRecipeTransferError transferRecipe(CraftingMenu container, RecipeHolder<CraftingRecipe> recipe,
                                               IRecipeSlotsView recipeSlots, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        IRecipeTransferError vanillaError =
                vanilla.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        if (vanillaError == null || vanillaError.getType() != IRecipeTransferError.Type.USER_FACING) {
            return vanillaError; // direct transfer worked (or an internal error we don't own)
        }
        if (ClientJobTracker.hasActiveJob()
                || !DeferredCraftableCache.isSolvable(recipe, GridContext.CRAFTING_TABLE)) {
            return vanillaError; // busy or truly unsolvable: keep JEI's red missing-items
        }
        if (doTransfer) {
            DeferredClickState.sendStartPacket(recipe);
            return null;
        }
        return DEFERRED_AVAILABLE;
    }

    /** Cosmetic (still clickable) yellow state inviting the deferred craft. */
    private static class DeferredCraftError implements IRecipeTransferError {

        private static final int TRANSLUCENT_YELLOW = 0x80FFE000;

        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public int getButtonHighlightColor() {
            return TRANSLUCENT_YELLOW;
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("sprawlcrafting.jei.deferred")
                    .withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.translatable("sprawlcrafting.jei.deferred.click")
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
