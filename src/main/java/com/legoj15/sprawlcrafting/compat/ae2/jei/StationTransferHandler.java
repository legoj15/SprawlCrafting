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
//? if >=1.21.11 {
/*import mezz.jei.api.recipe.types.IRecipeType;*/
//?} else {
import mezz.jei.api.recipe.RecipeType;
//?}
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
 * (needs system on), as the crafting-table handler does. AE2 15 (1.20.1) and AE2 26.x do have one,
 * passed in as the {@code stock} delegate ({@code Ae2JeiStock}); wherever the engine adds nothing,
 * stock answers instead: integration off, an offline terminal (the server plans it as the 2x2
 * inventory), a direct craft (AE2 lays the recipe into the terminal grid from the network) and an
 * unsolvable recipe with the needs helper off. The deferred and gather paths stay the engine's either
 * way; on 26.x the client cannot tell a direct craft from an unsolvable recipe (it only has the
 * server-synced deferred set), so there AE2's simulated answer decides — see the gated branch in
 * {@code transferRecipe}.
 *
 * <p>Generic over the menu class (from {@code StationStorages.menus()}), so it never links AE2.
 */
// JEI's recipe type R is RecipeHolder<CraftingRecipe> on 1.20.2+, but the bare CraftingRecipe on 1.20.1
// (JEI 15); the 1.20.1 branch wraps it in the RecipeHolder shim at the top of transferRecipe.
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
    // The menu's own (AE2 15 on 1.20.1, AE2 26.x) handler for stock behaviour, or null where AE2 ships
    // none (19.x, the 1.21.1 line); the stock calls below are dead branches then.
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
        //? if >=1.20.2 {
        RecipeHolder<CraftingRecipe> holder = recipe;
        //?} else {
        /*RecipeHolder<CraftingRecipe> holder = RecipeHolder.of(recipe);*/
        //?}
        if (!SprawlConfig.get().jeiIntegration()) {
            // Integration off: stock behaviour where AE2 has some, else no button, as without this handler.
            return stock != null
                    ? busyOr(() -> stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer))
                    : helper.createInternalError();
        }
        // An offline terminal is no station: the server plans it as the 2x2 inventory (GridContext.current),
        // so classify the same way rather than offering 3x3 work the server would refuse — and with a stock
        // handler, hand the recipe to AE2 entirely, exactly what a terminal user without us would get.
        boolean usable = DeferredCraftableCache.stationUsable();
        if (!usable && stock != null) {
            return busyOr(() -> stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer));
        }
        //? if >=1.21.11 {
        /*// 26.x: the client has no recipe contents, so DeferredCraftableCache.classify can only answer
        // DEFERRED (the server's synced set says so) or UNSOLVABLE — its "not deferred" bucket holds both
        // directly craftable and truly unsolvable recipes, indistinguishably. AE2's simulated result
        // decides: asked with doTransfer=false it returns null (or an internal error) when inventory +
        // network hold everything — a direct craft — and a user-facing / cosmetic error when a slot is
        // missing or only AE2-autocraftable (the planner ignores AE2 patterns, as on 1.20.1): the gather
        // list, or AE2's own report with the helper off. The simulation never transfers, so only the
        // performing calls are busy-guarded; the gather list stays available mid-job, as at the table.
        Craftability craftability = DeferredCraftableCache.classify(holder, GridContext.CRAFTING_TABLE);
        if (craftability == Craftability.DEFERRED) {
            // Busy first: a second craft mid-job would race the running one for the same materials.
            if (ClientJobTracker.hasActiveJob()) {
                return helper.createUserErrorWithTooltip(Component.translatable("sprawlcrafting.craft.busy"));
            }
            // A recipe only the protected tier can make (issue #40) confirms through the protected
            // start packet; the button tooltip carries the special-items warning.
            boolean protectedCraft = DeferredCraftableCache.isProtectedOnly(holder);
            if (doTransfer) {
                if (protectedCraft) {
                    DeferredClickState.sendProtectedStartPacket(holder);
                } else {
                    DeferredClickState.sendStartPacket(holder);
                }
                return null;
            }
            return protectedCraft ? DeferredTransfers.protectedDeferredAvailable(holder, GridContext.CRAFTING_TABLE)
                    : DeferredTransfers.DEFERRED_AVAILABLE;
        }
        // stock is never null here: StationJeiPlugin's >=1.21.11 branch always builds AE2 26.x's handler.
        IRecipeTransferError simulated =
                stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, false);
        boolean missing = simulated != null && (simulated.getType() == IRecipeTransferError.Type.USER_FACING
                || simulated.getType() == IRecipeTransferError.Type.COSMETIC);
        if (!missing) {
            // Everything present (or another AE2 answer, passed through): AE2's own transfer handles it.
            // Drawing the button needs no second scan: the simulation already is AE2's answer.
            return doTransfer || ClientJobTracker.hasActiveJob()
                    ? busyOr(() -> stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer))
                    : simulated;
        }
        if (SprawlConfig.get().needsSystem() && MissingIngredients.canRequestByRecipe()) {
            if (doTransfer) {
                // Same as the crafting-table handler: open after JEI's click handling settles.
                MissingIngredients.requestOpenNextTick(
                        () -> MissingIngredientsView.open(holder, GridContext.CRAFTING_TABLE));
                return null;
            }
            return DeferredTransfers.gatherListError(holder);
        }
        // Helper off, or a server that can't answer the gather request (#42): AE2's own missing report (or its partial transfer on the click), same shape as above.
        return doTransfer || ClientJobTracker.hasActiveJob()
                ? busyOr(() -> stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer))
                : simulated;*/
        //?} else {
        GridContext grid = usable ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        Craftability craftability = DeferredCraftableCache.classify(holder, grid);
        if (craftability == Craftability.DIRECT || craftability == Craftability.DEFERRED) {
            // Busy first, for green too: AE2's grid fill mid-job would pull network items the job planned on.
            if (ClientJobTracker.hasActiveJob()) {
                return helper.createUserErrorWithTooltip(Component.translatable("sprawlcrafting.craft.busy"));
            }
            if (craftability == Craftability.DIRECT && stock != null) {
                // AE2 lays the recipe into the terminal grid straight from the network: stock behaviour.
                return busyOr(() -> stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer));
            }
            // A recipe only the protected tier can make (issue #40) confirms through the protected
            // start packet; the button tooltip carries the special-items warning. A direct craft is
            // never protected-only — the plain pool covers it — so it keeps the plain start (AE2 19.x
            // has no stock handler; its green button starts a single-step job, as before).
            boolean protectedCraft = craftability == Craftability.DEFERRED
                    && DeferredCraftableCache.isProtectedOnly(holder);
            if (doTransfer) {
                if (protectedCraft) {
                    DeferredClickState.sendProtectedStartPacket(holder);
                } else {
                    DeferredClickState.sendStartPacket(holder);
                }
                return null;
            }
            return craftability == Craftability.DEFERRED
                    ? (protectedCraft ? DeferredTransfers.protectedDeferredAvailable(holder, grid)
                            : DeferredTransfers.DEFERRED_AVAILABLE)
                    : null;
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
            return busyOr(() -> stock.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer));
        }
        return helper.createUserErrorWithTooltip(Component.translatable("jei.tooltip.error.recipe.transfer.missing"));
        //?}
    }

    /**
     * Every hand-off to AE2's own handler goes through here: while a job runs it is refused with the busy
     * tooltip, because AE2's fill pulls from the inventory and the network into a grid the running job
     * cannot see (1.21.1, with no stock handler, shows busy for the same clicks). Our own gather list is
     * not a hand-off and stays available mid-job, as at the crafting table.
     */
    private IRecipeTransferError busyOr(java.util.function.Supplier<IRecipeTransferError> toStock) {
        if (ClientJobTracker.hasActiveJob()) {
            return helper.createUserErrorWithTooltip(Component.translatable("sprawlcrafting.craft.busy"));
        }
        return toStock.get();
    }
}
