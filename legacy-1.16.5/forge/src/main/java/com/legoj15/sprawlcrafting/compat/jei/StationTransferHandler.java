package com.legoj15.sprawlcrafting.compat.jei;

import java.util.function.Supplier;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.GridContext;

import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * JEI's "+" button in a crafting station's screen (the AE2 Crafting Terminal, which has no recipe book).
 * Port of the root tree's handler to JEI 7. AE2 8.4.7 ships its own handler for the terminal, passed in as
 * {@code stock}; wherever the engine adds nothing, stock answers: integration off, an unusable terminal
 * (the server plans it as the 2x2 inventory), a direct craft (AE2 lays the recipe into the terminal grid
 * from the network) and an unsolvable recipe with the needs helper off. The deferred (yellow) and gather
 * (orange) paths are ours either way.
 *
 * <p>Generic over the menu class (from {@code StationStorages.menus()}) and holding AE2's handler only as
 * a JEI interface, so this class never links AE2. A null {@code stock} (AE2's internal class changed shape)
 * means the engine takes every recipe, as on the 1.21.1 line.
 */
public class StationTransferHandler<C extends Container> implements IRecipeTransferHandler<C> {

    private final Class<C> menuClass;
    private final IRecipeTransferHandlerHelper helper;
    private final IRecipeTransferHandler<C> stock;

    public StationTransferHandler(Class<C> menuClass, IRecipeTransferHandlerHelper helper,
                                  IRecipeTransferHandler<C> stock) {
        this.menuClass = menuClass;
        this.helper = helper;
        this.stock = stock;
    }

    @Override
    public Class<C> getContainerClass() {
        return menuClass;
    }

    @Override
    public IRecipeTransferError transferRecipe(C container, Object recipe, IRecipeLayout layout, PlayerEntity player,
                                               boolean maxTransfer, boolean doTransfer) {
        Supplier<IRecipeTransferError> toStock =
                () -> stock.transferRecipe(container, recipe, layout, player, maxTransfer, doTransfer);
        if (!SprawlConfig.get().jeiIntegration() || !(recipe instanceof IRecipe)) {
            // Integration off (or a recipe object we cannot plan): stock behaviour, else no button.
            return stock != null ? busyOr(toStock) : helper.createInternalError();
        }
        IRecipe<?> craft = (IRecipe<?>) recipe;
        // An unusable terminal is no station: the server plans it as the 2x2 inventory (GridContext.current),
        // so hand the recipe to AE2 entirely, exactly what a terminal user without us would get. This is also
        // the path against a server without the station marker, where stationUsable() is always false.
        boolean usable = DeferredCraftableCache.stationUsable();
        if (!usable && stock != null) {
            return busyOr(toStock);
        }
        GridContext grid = usable ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        Craftability craftability = DeferredCraftableCache.classify(craft, grid);
        if (craftability == Craftability.DIRECT || craftability == Craftability.DEFERRED) {
            // Busy first, for green too: AE2's grid fill mid-job would pull network items the job planned on.
            if (ClientJobTracker.hasActiveJob()) {
                return busy();
            }
            if (craftability == Craftability.DIRECT && stock != null) {
                return stock.transferRecipe(container, recipe, layout, player, maxTransfer, doTransfer);
            }
            if (doTransfer) {
                DeferredClickState.sendStartPacket(craft);
                return null;
            }
            return craftability == Craftability.DEFERRED ? DeferredCraftingTransferHandler.deferredAvailable() : null;
        }
        if (craftability == Craftability.UNSOLVABLE && SprawlConfig.get().needsSystem()) {
            if (doTransfer) {
                // Same as the crafting-table handler: open after JEI's click handling settles.
                MissingIngredients.requestOpenNextTick(() -> MissingIngredientsView.open(craft, grid));
                return null;
            }
            return DeferredCraftingTransferHandler.gatherListError(craft, grid);
        }
        if (stock != null) {
            // Unsolvable with the needs helper off: AE2's own report of what the grid cannot fill.
            return busyOr(toStock);
        }
        return helper.createUserErrorWithTooltip(
                new TranslationTextComponent("jei.tooltip.error.recipe.transfer.missing"));
    }

    /**
     * Every hand-off to AE2's own handler goes through here: while a job runs it is refused with the busy
     * tooltip, because AE2's fill pulls from the inventory and the network into a grid the running job
     * cannot see. Our own gather list is not a hand-off and stays available mid-job, as at the crafting
     * table.
     */
    private IRecipeTransferError busyOr(Supplier<IRecipeTransferError> toStock) {
        return ClientJobTracker.hasActiveJob() ? busy() : toStock.get();
    }

    private IRecipeTransferError busy() {
        return helper.createUserErrorWithTooltip(new TranslationTextComponent("sprawlcrafting.craft.busy"));
    }
}
