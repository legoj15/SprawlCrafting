package com.legoj15.sprawlcrafting.forge.compat.jei;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.client.ServerPresence;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;

/**
 * JEI "+" for a network crafting terminal (the AE2 Crafting Terminal, issue #2). It sits in front
 * of AE2's own transfer handler instead of replacing it ({@link DeferredHandlerRegistry.FrontHandler}):
 * <ul>
 *   <li><b>Direct</b> (all ingredients in the player's inventory plus the network) — AE2's handler,
 *       unchanged: it lays the recipe into the terminal grid from the network, which is what a
 *       terminal user expects.</li>
 *   <li><b>Deferred</b> (makeable via intermediates — including only via the player's protected
 *       stacks, issue #40, which gets the warning tooltip and the protected start like at a table)
 *       — SprawlCrafting's engine, yellow button.</li>
 *   <li><b>Unsolvable</b> — the orange gather button / greyed "+" listing missing raws, exactly as at
 *       a table; with the needs helper off, AE2's handler (stock behaviour).</li>
 * </ul>
 * Whenever the server cannot honour the network (no SprawlCrafting, or a version without the
 * network-terminal capability — {@link ServerPresence#networkTerminals}) or JEI integration is off,
 * AE2's handler takes every recipe, so a modless server sees stock AE2 behaviour.
 *
 * <p>Registered only in {@link DeferredHandlerRegistry} (never JEI's own table, which keeps AE2's
 * handler) and only when AE2 is loaded; it names no AE2 type — the container class comes from
 * {@code Class.forName} — so this lives with the other JEI code, not in {@code compat.ae2}.
 */
public final class NetworkTerminalTransferHandler extends DeferredCraftTransferHandler<Container>
        implements DeferredHandlerRegistry.FrontHandler {

    private final IRecipeTransferHandlerHelper helper;
    /** AE2's handler for the container being resolved, refreshed by RecipeRegistryMixin on every lookup. */
    private volatile IRecipeTransferHandler<Container> original;

    public NetworkTerminalTransferHandler(Class<Container> containerClass, IRecipeTransferHandlerHelper helper) {
        super(containerClass, helper, GridContext.CRAFTING_TABLE);
        this.helper = helper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setOriginal(Object handler) {
        original = handler instanceof IRecipeTransferHandler ? (IRecipeTransferHandler<Container>) handler : null;
    }

    @Override
    public IRecipeTransferError transferRecipe(Container container, IRecipeLayout recipeLayout,
                                               EntityPlayer player, boolean maxTransfer, boolean doTransfer) {
        IRecipeTransferHandler<Container> stock = original;
        // An unpowered terminal is no station: the server plans it as the 2x2 inventory and would
        // refuse 3x3 work, so AE2's handler takes everything until the network is back.
        boolean engine = ServerPresence.networkTerminals() && SprawlConfig.jeiIntegration
                && NetworkTerminals.usable(player);
        if (!engine) {
            JeiTintState.put(recipeLayout, null);
            // No usable engine: AE2's handler alone, or — when AE2 registered none — no "+" at all,
            // exactly as if SprawlCrafting were absent.
            return stock != null
                    ? stock.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer)
                    : helper.createInternalError();
        }
        CraftPlanner.Craftability craftability = classify(recipeLayout, player);
        if (craftability == null) {
            return helper.createInternalError();
        }
        boolean toStock = craftability == CraftPlanner.Craftability.DIRECT
                || (craftability == CraftPlanner.Craftability.UNSOLVABLE && !SprawlConfig.needsSystem);
        if (toStock) {
            JeiTintState.put(recipeLayout, null);
            if (stock != null) {
                return stock.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
            }
            if (craftability == CraftPlanner.Craftability.DIRECT) {
                // AE2 registered no handler (its JEI module disabled): the engine crafts the direct
                // recipe into the inventory rather than leaving a dead button. The base handler's
                // direct path would send a grid placement the server refuses at a terminal.
                if (doTransfer) {
                    IRecipe recipe = findRecipe(recipeLayout, outputStack(recipeLayout));
                    if (recipe != null) {
                        SprawlNetwork.startByRecipe(recipe.getRegistryName());
                    } else {
                        SprawlNetwork.startByResult(outputStack(recipeLayout));
                    }
                }
                return null;
            }
        }
        // Deferred or unsolvable: the base handler's engine / gather paths, planned over the
        // player's inventory plus the network (ClientPlanCache sees the terminal's network).
        return super.transferRecipe(container, recipeLayout, player, maxTransfer, doTransfer);
    }

    /**
     * Direct / deferred / unsolvable for the shown recipe at the 3x3 grid; null when it has no
     * output. Protected-aware (issue #40), like the base handler's own paths: a recipe only the
     * player's protected stacks can make is {@code DEFERRED} here too, so the terminal offers the
     * yellow deferred button (warning tooltip, protected start on click) instead of routing it to
     * AE2's handler, which would grey it out or try to fill the grid from plain stock only. The
     * by-result fallback keeps the plain session's direct/deferred split and widens only its
     * shortfalls, reusing {@link ClientPlanCache}'s protected tier rather than a second classify.
     */
    private static CraftPlanner.Craftability classify(IRecipeLayout recipeLayout, EntityPlayer player) {
        ItemStack result = outputStack(recipeLayout);
        if (result.isEmpty()) {
            return null;
        }
        IRecipe recipe = findRecipe(recipeLayout, result);
        if (recipe != null) {
            return ClientPlanCache.classify(player, GridContext.CRAFTING_TABLE, recipe);
        }
        CraftPlanner.Session session = ClientPlanCache.get(player, GridContext.CRAFTING_TABLE);
        CraftPlanner.PlanOutcome outcome = session.planByResult(result);
        if (outcome instanceof CraftPlanner.PlanOutcome.Planned) {
            return ((CraftPlanner.PlanOutcome.Planned) outcome).job().steps().size() == 1
                    ? CraftPlanner.Craftability.DIRECT : CraftPlanner.Craftability.DEFERRED;
        }
        return ClientPlanCache.canDeferCraft(player, GridContext.CRAFTING_TABLE, result)
                ? CraftPlanner.Craftability.DEFERRED : CraftPlanner.Craftability.UNSOLVABLE;
    }
}
