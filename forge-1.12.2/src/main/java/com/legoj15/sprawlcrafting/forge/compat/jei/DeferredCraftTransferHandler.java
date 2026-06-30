package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ItemKey;
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
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;

/**
 * Routes JEI's recipe-transfer "+" button on a vanilla crafting recipe into a SprawlCrafting
 * deferred craft. Registered for {@link ContainerWorkbench} (3x3) and {@code ContainerPlayer}
 * (2x2) on the crafting category, it overwrites JEI's built-in crafting transfer handler (JEI's
 * registry is last-write-wins, and third-party plugins register after the vanilla one).
 *
 * <p>On a real press ({@code doTransfer}) it identifies the specific recipe shown in the layout and
 * sends its registry name to the server, so the solver uses that exact recipe rather than picking
 * whichever producer of the same item resolves first (which, in modpacks with many alternative
 * recipes, can differ from what the player clicked). On JEI's validation pass it enables the button
 * only when the specific displayed recipe can be made from current stock (directly or via
 * intermediates), so unsolvable recipes grey out as players expect. Unlike vanilla "+", even a
 * directly-craftable recipe runs through the engine — the result lands in the inventory rather than
 * the grid, after the engine's half-second cadence.
 */
public class DeferredCraftTransferHandler<C extends Container> implements IRecipeTransferHandler<C> {

    private final Class<C> containerClass;
    private final IRecipeTransferHandlerHelper helper;
    private final GridContext gridOverride;

    public DeferredCraftTransferHandler(Class<C> containerClass, IRecipeTransferHandlerHelper helper) {
        this(containerClass, helper, null);
    }

    /**
     * @param gridOverride if non-null, forces this grid context instead of inferring from
     *                     {@code instanceof ContainerWorkbench}. Required for modded crafting
     *                     containers that provide a 3x3 grid without extending ContainerWorkbench
     *                     (e.g. TConstruct's Crafting Station).
     */
    public DeferredCraftTransferHandler(Class<C> containerClass, IRecipeTransferHandlerHelper helper,
                                        GridContext gridOverride) {
        this.containerClass = containerClass;
        this.helper = helper;
        this.gridOverride = gridOverride;
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
            return helper.createInternalError();
        }
        GridContext grid = gridOverride != null ? gridOverride
                : container instanceof ContainerWorkbench
                ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        IRecipe recipe = findRecipe(recipeLayout, result);
        if (doTransfer) {
            if (recipe != null) {
                SprawlNetwork.startByRecipe(recipe.getRegistryName());
            } else {
                SprawlNetwork.startByResult(result);
            }
            return null;
        }
        CraftPlanner.Session session = ClientPlanCache.get(player, grid);
        if (recipe != null) {
            if (session.isSolvable(recipe)) {
                return null;
            }
            return new MissingResourcesError(session.shortfall(recipe));
        }
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

    /**
     * Identifies the specific recipe shown in the layout by matching its displayed output and
     * inputs against the recipe registry. JEI 4.x does not expose the recipe object through its
     * public API, so we recover it by checking which registered recipe accepts the exact set of
     * items JEI is displaying. Returns null if no match is found (the caller falls back to
     * result-based resolution).
     */
    private static IRecipe findRecipe(IRecipeLayout layout, ItemStack result) {
        Map<Integer, ? extends IGuiIngredient<ItemStack>> guiIngredients =
                layout.getItemStacks().getGuiIngredients();
        List<ItemStack> displayedInputs = new ArrayList<ItemStack>();
        for (int slot = 1; slot <= 9; slot++) {
            IGuiIngredient<ItemStack> gui = guiIngredients.get(Integer.valueOf(slot));
            if (gui != null && gui.isInput()) {
                ItemStack displayed = gui.getDisplayedIngredient();
                if (displayed != null && !displayed.isEmpty()) {
                    displayedInputs.add(displayed);
                }
            }
        }
        if (displayedInputs.isEmpty()) {
            return null;
        }
        ItemKey resultKey = ItemKey.of(result);
        for (IRecipe candidate : CraftingManager.REGISTRY) {
            if (candidate.isDynamic() || candidate.getRegistryName() == null) {
                continue;
            }
            ItemStack output = candidate.getRecipeOutput();
            if (output.isEmpty() || !ItemKey.of(output).equals(resultKey)) {
                continue;
            }
            NonNullList<Ingredient> ingredients = candidate.getIngredients();
            int nonEmpty = 0;
            for (Ingredient ing : ingredients) {
                if (ing != Ingredient.EMPTY) {
                    nonEmpty++;
                }
            }
            if (nonEmpty != displayedInputs.size()) {
                continue;
            }
            if (ingredientsAccept(ingredients, displayedInputs)) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether each displayed input is accepted by a distinct non-empty ingredient. */
    private static boolean ingredientsAccept(NonNullList<Ingredient> ingredients,
                                              List<ItemStack> inputs) {
        boolean[] used = new boolean[inputs.size()];
        for (Ingredient ingredient : ingredients) {
            if (ingredient == Ingredient.EMPTY) {
                continue;
            }
            boolean matched = false;
            for (int i = 0; i < inputs.size(); i++) {
                if (!used[i] && ingredient.apply(inputs.get(i))) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }
}
