package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.Map;

import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.ItemStack;

/**
 * Routes JEI's recipe-transfer "+" button on a vanilla crafting recipe into a SprawlCrafting
 * deferred craft. Registered for {@link ContainerWorkbench} (3x3) and {@code ContainerPlayer}
 * (2x2) on the crafting category, it overwrites JEI's built-in crafting transfer handler (JEI's
 * registry is last-write-wins, and third-party plugins register after the vanilla one).
 *
 * <p>On a real press ({@code doTransfer}) it sends the displayed result item to the server, which
 * picks the best grid-fitting producer and starts the chain. On JEI's validation pass it enables
 * the button only when the item can actually be made from current stock (directly or via
 * intermediates), so unsolvable recipes grey out as players expect. Unlike vanilla "+", even a
 * directly-craftable recipe runs through the engine — the result lands in the inventory rather than
 * the grid, after the engine's half-second cadence.
 */
public class DeferredCraftTransferHandler<C extends Container> implements IRecipeTransferHandler<C> {

    private final Class<C> containerClass;
    private final IRecipeTransferHandlerHelper helper;

    public DeferredCraftTransferHandler(Class<C> containerClass, IRecipeTransferHandlerHelper helper) {
        this.containerClass = containerClass;
        this.helper = helper;
    }

    @Override
    public Class<C> getContainerClass() {
        return containerClass;
    }

    @Override
    public IRecipeTransferError transferRecipe(C container, IRecipeLayout recipeLayout,
                                               EntityPlayer player, boolean maxTransfer, boolean doTransfer) {
        ItemStack result = outputStack(recipeLayout);
        if (result.isEmpty()) {
            // Not a recipe whose result we can read; let JEI hide our button rather than mislead.
            return helper.createInternalError();
        }
        GridContext grid = container instanceof ContainerWorkbench
                ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        if (doTransfer) {
            SprawlNetwork.startByResult(result);
            return null;
        }
        // Validation: enable "+" when this can be (deferred-)crafted; otherwise grey the button and,
        // on hover, list the raw materials still needed (see MissingResourcesError) — that is how a
        // player sees a JEI recipe's missing ingredients.
        CraftPlanner.Session session = ClientPlanCache.get(player, grid);
        if (session.canDeferCraft(result)) {
            return null;
        }
        return new MissingResourcesError(session.shortfallByResult(result));
    }

    /** The crafting category's output is GUI ingredient slot 0; fall back to the first output slot. */
    private static ItemStack outputStack(IRecipeLayout layout) {
        Map<Integer, ? extends IGuiIngredient<ItemStack>> ingredients =
                layout.getItemStacks().getGuiIngredients();
        IGuiIngredient<ItemStack> output = ingredients.get(Integer.valueOf(0));
        if (output != null && !output.isInput()) {
            ItemStack displayed = output.getDisplayedIngredient();
            if (displayed != null && !displayed.isEmpty()) {
                return displayed;
            }
        }
        for (IGuiIngredient<ItemStack> ingredient : ingredients.values()) {
            if (!ingredient.isInput()) {
                ItemStack displayed = ingredient.getDisplayedIngredient();
                if (displayed != null && !displayed.isEmpty()) {
                    return displayed;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
