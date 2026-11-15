package com.legoj15.sprawlcrafting.compat.ae2.jei;

import java.util.Optional;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.compat.jei.DeferredTransfers;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.GridContext;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * JEI's "+" button in a crafting station's screen (the AE2 Crafting Terminal, which has no recipe
 * book). AE2 19.x (the 1.21.1 line) has no JEI module of its own, so every craft goes through the
 * engine: green (all direct ingredients in inventory + storage) and yellow (needs intermediates) both
 * start a job, the single-step green one simply crafts into the inventory; red opens the gather list
 * (needs system on), as the crafting-table handler does. AE2 15 (1.20.1) does have one, passed in as
 * the {@code stock} delegate ({@code Ae2JeiStock}); wherever the engine adds nothing, stock answers
 * instead: integration off, an offline terminal (the server plans it as the 2x2 inventory), a direct
 * craft (AE2 lays the recipe into the terminal grid from the network) and an unsolvable recipe with
 * the needs helper off. The deferred and gather paths stay the engine's either way.
 *
 * <p>Generic over the menu class (from {@code StationStorages.menus()}), so it never links AE2.
 */
// JEI's recipe type R is RecipeHolder<CraftingRecipe> on 1.20.2+, but the bare CraftingRecipe on 1.20.1
// (JEI 15); the 1.20.1 branch wraps it in the RecipeHolder shim at the top of transferRecipe. This
// package never compiles above 1.21.1, so no 1.21.11 IRecipeType branch is needed here.
//? if >=1.20.2 {
public class StationTransferHandler<C extends AbstractContainerMenu>
        implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {
//?} else {
/*public class StationTransferHandler<C extends AbstractContainerMenu>
        implements IRecipeTransferHandler<C, CraftingRecipe> {*/
//?}

    private final Class<C> menuClass;
    private final MenuType<C> menuType;
    private final IRecipeTransferHandlerHelper helper;
    //? if >=1.20.2 {
    // The menu's own (AE2 15) handler for stock behaviour, or null where AE2 ships none (19.x); the
    // stock calls below are dead branches then. Null on 1.21.1, the only line that compiles there.
    private final IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> stock;
    //?} else {
    /*private final IRecipeTransferHandler<C, CraftingRecipe> stock;*/
    //?}

    //? if >=1.20.2 {
    public StationTransferHandler(Class<C> menuClass, MenuType<C> menuType, IRecipeTransferHandlerHelper helper,
                                  IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> stock) {
    //?} else {
    /*public StationTransferHandler(Class<C> menuClass, MenuType<C> menuType, IRecipeTransferHandlerHelper helper,
                                  IRecipeTransferHandler<C, CraftingRecipe> stock) {*/
    //?}
        this.menuClass = menuClass;
        this.menuType = menuType;
        this.helper = helper;
        this.stock = stock;
    }

    @Override
    public Class<? extends C> getContainerClass() {
        return menuClass;
    }

    @Override
    public Optional<MenuType<C>> getMenuType() {
        return Optional.of(menuType);
    }

    @Override
    //? if >=1.20.2 {
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
        //? if >=1.20.2 {
        RecipeHolder<CraftingRecipe> holder = recipe;
        //?} else {
        /*RecipeHolder<CraftingRecipe> holder = RecipeHolder.of(recipe);*/
        //?}
        if (!SprawlConfig.get().jeiIntegration()) {
            // Integration off: stock behaviour where AE2 has some, else no button, as without this handler.
            return stock != null
                    ? stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer)
                    : helper.createInternalError();
        }
        // An offline terminal is no station: the server plans it as the 2x2 inventory (GridContext.current),
        // so classify the same way rather than offering 3x3 work the server would refuse — and with a stock
        // handler, hand the recipe to AE2 entirely, exactly what a terminal user without us would get.
        boolean usable = DeferredCraftableCache.stationUsable();
        if (!usable && stock != null) {
            return stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        }
        GridContext grid = usable ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        Craftability craftability = DeferredCraftableCache.classify(holder, grid);
        if (craftability == Craftability.DIRECT || craftability == Craftability.DEFERRED) {
            // Busy first, for green too: AE2's grid fill mid-job would pull network items the job planned on.
            if (ClientJobTracker.hasActiveJob()) {
                return helper.createUserErrorWithTooltip(Component.translatable("sprawlcrafting.craft.busy"));
            }
            if (craftability == Craftability.DIRECT && stock != null) {
                // AE2 lays the recipe into the terminal grid straight from the network: stock behaviour.
                return stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
            }
            if (doTransfer) {
                DeferredClickState.sendStartPacket(holder);
                return null;
            }
            return craftability == Craftability.DEFERRED ? DeferredTransfers.DEFERRED_AVAILABLE : null;
        }
        if (craftability == Craftability.UNSOLVABLE && SprawlConfig.get().needsSystem()) {
            if (doTransfer) {
                // Same as the crafting-table handler: open after JEI's click handling settles.
                MissingIngredients.requestOpenNextTick(
                        () -> MissingIngredientsView.open(holder, grid));
                return null;
            }
            return DeferredTransfers.gatherListError(holder);
        }
        if (stock != null) {
            // Unsolvable with the needs helper off: AE2's own report of what the grid cannot fill.
            return stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        }
        return helper.createUserErrorWithTooltip(Component.translatable("jei.tooltip.error.recipe.transfer.missing"));
    }
}
