package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ItemKey;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;

/**
 * Routes JEI's recipe-transfer "+" button on a vanilla crafting recipe. For recipes that need
 * intermediate steps (DEFERRED), clicking the button starts a SprawlCrafting deferred craft.
 * For recipes where all ingredients are already in stock (DIRECT), clicking fills the crafting
 * grid directly — matching vanilla JEI's behaviour — so simple one-step crafts don't go through
 * the engine.
 *
 * <p>Registered for {@link ContainerWorkbench} (3x3) and {@code ContainerPlayer} (2x2) on the
 * crafting category. On JEI's validation pass the button is enabled when the recipe can be made
 * from current stock (directly or via intermediates).
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
                CraftPlanner.Session session = ClientPlanCache.get(player, grid);
                // Vanilla grid-fill only sources from the player inventory, so reserve it for recipes
                // the player can make from their own slots. A recipe that is direct only thanks to the
                // station's connected chest goes through the engine instead (which pulls the chest).
                if (session.classify(recipe) == CraftPlanner.Craftability.DIRECT
                        && CraftExecutor.canCraftFromPlayerInventory(player, recipe)
                        && vanillaGridFill(container, recipeLayout, player, maxTransfer)) {
                    return null;
                }
            }
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

    /**
     * Fills the crafting grid using Minecraft's drag mechanic: right-drag places 1 per slot
     * (single craft), left-drag distributes evenly (shift-click / max transfer). Ingredients
     * that share the same item across multiple grid positions are grouped into a single drag
     * so they split correctly from one inventory stack.
     */
    private boolean vanillaGridFill(C container, IRecipeLayout recipeLayout, EntityPlayer player,
                                    boolean maxTransfer) {
        int gridStart, gridSize, gridWidth, invStart, invEnd;
        if (container instanceof ContainerWorkbench) {
            gridStart = 1; gridSize = 9; gridWidth = 3;
            int total = container.inventorySlots.size();
            invStart = total - 36; invEnd = total - 1;
        } else if (container instanceof ContainerPlayer) {
            gridStart = 1; gridSize = 4; gridWidth = 2;
            invStart = 9; invEnd = 44;
        } else if (GridContext.isModded3x3Crafter(container)) {
            gridStart = 1; gridSize = 9; gridWidth = 3;
            int total = container.inventorySlots.size();
            invStart = total - 36; invEnd = total - 1;
        } else {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        int wid = container.windowId;

        if (!player.inventory.getItemStack().isEmpty()) {
            for (int i = invStart; i <= invEnd; i++) {
                if (!container.getSlot(i).getHasStack()) {
                    mc.playerController.windowClick(wid, i, 0, ClickType.PICKUP, player);
                    break;
                }
            }
            if (!player.inventory.getItemStack().isEmpty()) {
                return false;
            }
        }

        for (int i = gridStart; i < gridStart + gridSize; i++) {
            if (container.getSlot(i).getHasStack()) {
                mc.playerController.windowClick(wid, i, 0, ClickType.QUICK_MOVE, player);
            }
        }

        Map<Integer, ? extends IGuiIngredient<ItemStack>> guiIngredients =
                recipeLayout.getItemStacks().getGuiIngredients();
        Map<ItemKey, List<Integer>> slotsByIngredient = new LinkedHashMap<ItemKey, List<Integer>>();

        for (int jeiSlot = 1; jeiSlot <= 9; jeiSlot++) {
            IGuiIngredient<ItemStack> gui = guiIngredients.get(Integer.valueOf(jeiSlot));
            if (gui == null || !gui.isInput()) {
                continue;
            }
            List<ItemStack> options = gui.getAllIngredients();
            if (options == null || options.isEmpty()) {
                continue;
            }

            int jeiRow = (jeiSlot - 1) / 3;
            int jeiCol = (jeiSlot - 1) % 3;
            if (jeiCol >= gridWidth || jeiRow >= (gridSize / gridWidth)) {
                continue;
            }
            int containerSlot = gridStart + jeiRow * gridWidth + jeiCol;

            ItemKey matchKey = null;
            for (int inv = invStart; inv <= invEnd && matchKey == null; inv++) {
                ItemStack inSlot = container.getSlot(inv).getStack();
                if (inSlot.isEmpty()) {
                    continue;
                }
                ItemKey invKey = ItemKey.of(inSlot);
                for (ItemStack option : options) {
                    if (!option.isEmpty() && invKey.equals(ItemKey.of(option))) {
                        matchKey = invKey;
                        break;
                    }
                }
            }

            if (matchKey == null) {
                return false;
            }

            List<Integer> slots = slotsByIngredient.get(matchKey);
            if (slots == null) {
                slots = new ArrayList<Integer>();
                slotsByIngredient.put(matchKey, slots);
            }
            slots.add(containerSlot);
        }

        int dragStart = maxTransfer ? 0 : 4;
        int dragAdd   = maxTransfer ? 1 : 5;
        int dragEnd   = maxTransfer ? 2 : 6;

        for (Map.Entry<ItemKey, List<Integer>> entry : slotsByIngredient.entrySet()) {
            ItemKey ingredient = entry.getKey();
            List<Integer> gridSlots = entry.getValue();

            for (int inv = invStart; inv <= invEnd; inv++) {
                ItemStack inSlot = container.getSlot(inv).getStack();
                if (inSlot.isEmpty() || !ItemKey.of(inSlot).equals(ingredient)) {
                    continue;
                }

                mc.playerController.windowClick(wid, inv, 0, ClickType.PICKUP, player);
                if (player.inventory.getItemStack().isEmpty()) {
                    continue;
                }

                mc.playerController.windowClick(wid, -999, dragStart, ClickType.QUICK_CRAFT, player);
                for (int gridSlot : gridSlots) {
                    mc.playerController.windowClick(wid, gridSlot, dragAdd, ClickType.QUICK_CRAFT, player);
                }
                mc.playerController.windowClick(wid, -999, dragEnd, ClickType.QUICK_CRAFT, player);

                if (!player.inventory.getItemStack().isEmpty()) {
                    mc.playerController.windowClick(wid, inv, 0, ClickType.PICKUP, player);
                }

                if (!maxTransfer) {
                    break;
                }
            }
        }

        return true;
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
