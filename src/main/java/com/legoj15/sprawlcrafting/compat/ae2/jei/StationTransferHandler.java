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
 * JEI's "+" button in a crafting station's screen (the AE2 Crafting Terminal, which has no recipe book
 * and, in AE2 19.x, no JEI module of its own). Every craft goes through the engine, because only the
 * engine draws from the station's storage: green (all direct ingredients in inventory + storage) and
 * yellow (needs intermediates) both start a job, the single-step green one simply crafts into the
 * inventory; red opens the gather list (needs system on), as the crafting-table handler does.
 *
 * <p>Generic over the menu class (from {@code StationStorages.menus()}), so it never links AE2.
 */
public class StationTransferHandler<C extends AbstractContainerMenu>
        implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {

    private final Class<C> menuClass;
    private final MenuType<C> menuType;
    private final IRecipeTransferHandlerHelper helper;

    public StationTransferHandler(Class<C> menuClass, MenuType<C> menuType, IRecipeTransferHandlerHelper helper) {
        this.menuClass = menuClass;
        this.menuType = menuType;
        this.helper = helper;
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
    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    @Override
    public IRecipeTransferError transferRecipe(C container, RecipeHolder<CraftingRecipe> recipe,
                                               IRecipeSlotsView recipeSlots, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        if (!SprawlConfig.get().jeiIntegration()) {
            return helper.createInternalError(); // integration off: no button, as without this handler
        }
        // An offline terminal is no station: the server plans it as the 2x2 inventory (GridContext.current),
        // so classify the same way rather than offering 3x3 work the server would refuse.
        GridContext grid = DeferredCraftableCache.stationUsable() ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        Craftability craftability = DeferredCraftableCache.classify(recipe, grid);
        if (craftability == Craftability.DIRECT || craftability == Craftability.DEFERRED) {
            if (ClientJobTracker.hasActiveJob()) {
                return helper.createUserErrorWithTooltip(Component.translatable("sprawlcrafting.craft.busy"));
            }
            if (doTransfer) {
                DeferredClickState.sendStartPacket(recipe);
                return null;
            }
            return craftability == Craftability.DEFERRED ? DeferredTransfers.DEFERRED_AVAILABLE : null;
        }
        if (craftability == Craftability.UNSOLVABLE && SprawlConfig.get().needsSystem()) {
            if (doTransfer) {
                // Same as the crafting-table handler: open after JEI's click handling settles.
                MissingIngredients.requestOpenNextTick(
                        () -> MissingIngredientsView.open(recipe, grid));
                return null;
            }
            return DeferredTransfers.gatherListError(recipe);
        }
        return helper.createUserErrorWithTooltip(Component.translatable("jei.tooltip.error.recipe.transfer.missing"));
    }
}
