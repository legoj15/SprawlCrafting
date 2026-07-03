package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.client.PendingGatherScreen;
import com.legoj15.sprawlcrafting.forge.client.ServerPresence;
import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.ExternalSlots;
import com.legoj15.sprawlcrafting.forge.mixin.jei.RecipeLayoutAccessor;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ItemKey;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.wrapper.ICraftingRecipeWrapper;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;

/**
 * Routes JEI's recipe-transfer "+" button on a vanilla crafting recipe. For recipes that need
 * intermediate steps (DEFERRED), clicking the button starts a SprawlCrafting deferred craft.
 * For recipes where all ingredients are already in stock (DIRECT), clicking fills the crafting
 * grid directly — matching vanilla JEI's behaviour — so simple one-step crafts don't go through
 * the engine.
 *
 * <p>Registered for {@link ContainerWorkbench} (3x3) and {@code ContainerPlayer} (2x2) on the
 * crafting category, plus a class-less structural fallback that {@code RecipeRegistryMixin} hands
 * back for any open container that structurally reads as a 3x3 crafter (a modded crafting station).
 * On JEI's validation pass the button is enabled when the recipe can be made from current stock
 * (directly or via intermediates). The direct fast-path fill derives its grid geometry from the
 * container's slots, so it works for any of these container shapes.
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
                : GridContext.isThreeByThreeCrafter(container)
                ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        IRecipe recipe = findRecipe(recipeLayout, result);
        CraftPlanner.Session session = ClientPlanCache.get(player, grid);
        if (doTransfer) {
            if (recipe != null) {
                if (!session.isSolvable(recipe)) {
                    // The orange gather button: "transferring" an unsolvable recipe opens the
                    // missing-resources screen (next tick — see PendingGatherScreen) instead of
                    // asking the engine for a craft it would only reject with a chat error.
                    // Gated live: a click landing after the needs helper was toggled off no-ops.
                    if (SprawlConfig.needsSystem && SprawlConfig.jeiIntegration) {
                        PendingGatherScreen.open(session.shortfall(recipe));
                    }
                    return null;
                }
                if (session.classify(recipe) == CraftPlanner.Craftability.DIRECT) {
                    // Preferred path: one C2S packet, atomic server-side placement with real
                    // vanilla recipe-book semantics (shift = craft-max, no desync). The server
                    // placer pulls from the player inventory AND the open station's bound chest —
                    // verified 2026-07-02 that stock TConstruct+JEI stages chest items into the
                    // grid, so routing chest-fed DIRECT crafts to the engine (as this handler once
                    // did) was a regression at stations, not parity.
                    if (ServerPresence.active()
                            && CraftExecutor.canCraftFromPlayerAndStation(player, recipe)) {
                        SprawlNetwork.placeRecipe(recipe.getRegistryName(),
                                container.windowId, maxTransfer);
                        return null;
                    }
                    // Modless-server fallback: the client-side click simulation can only reach the
                    // player's own slots, so chest-fed crafts still go to the engine below.
                    if (!ServerPresence.active()
                            && CraftExecutor.canCraftFromPlayerInventory(player, recipe)
                            && vanillaGridFill(container, recipeLayout, player, maxTransfer)) {
                        return null;
                    }
                }
            }
            if (recipe != null) {
                SprawlNetwork.startByRecipe(recipe.getRegistryName());
            } else {
                if (!session.canDeferCraft(result)) {
                    if (SprawlConfig.needsSystem && SprawlConfig.jeiIntegration) {
                        PendingGatherScreen.open(session.shortfallByResult(result));
                    }
                    return null;
                }
                SprawlNetwork.startByResult(result);
            }
            return null;
        }
        if (!SprawlConfig.jeiIntegration) {
            // Integration off (modern's jei toggle): direct recipes keep a working "+" — that's
            // core placement, not polish — and everything else greys out with JEI's own stock
            // message. No tint, no highlights, no gather button.
            JeiTintState.put(recipeLayout, null);
            boolean direct = recipe != null
                    && session.classify(recipe) == CraftPlanner.Craftability.DIRECT;
            return direct ? null : helper.createUserErrorWithTooltip(
                    I18n.format("jei.tooltip.error.recipe.transfer.missing"));
        }
        if (recipe != null) {
            if (session.isSolvable(recipe)) {
                // Solvable → no error object exists to carry state, so the button tint (yellow =
                // deferred multi-step, plain = direct move) rides the side-band cache instead.
                JeiTintState.put(recipeLayout,
                        session.classify(recipe) != CraftPlanner.Craftability.DIRECT
                                ? JeiTintState.Tint.DEFERRED : null);
                return null;
            }
            if (!SprawlConfig.needsSystem) {
                JeiTintState.put(recipeLayout, null);
                return helper.createUserErrorWithTooltip(
                        I18n.format("jei.tooltip.error.recipe.transfer.missing"));
            }
            JeiTintState.put(recipeLayout, JeiTintState.Tint.GATHER);
            return new MissingResourcesError(session.shortfall(recipe),
                    missingSlots(recipeLayout, player, session));
        }
        if (session.canDeferCraft(result)) {
            JeiTintState.put(recipeLayout, JeiTintState.Tint.DEFERRED);
            return null;
        }
        if (!SprawlConfig.needsSystem) {
            JeiTintState.put(recipeLayout, null);
            return helper.createUserErrorWithTooltip(
                    I18n.format("jei.tooltip.error.recipe.transfer.missing"));
        }
        JeiTintState.put(recipeLayout, JeiTintState.Tint.GATHER);
        return new MissingResourcesError(session.shortfallByResult(result),
                missingSlots(recipeLayout, player, session));
    }

    /**
     * Which recipe-layout input slots (1..9) deserve the red highlight when the recipe is
     * unsolvable. Two-tier, chain-aware: a slot is red when NONE of its accepted ingredients is in
     * stock (36 main slots + the open station's grid/chest — the solver's pool, reserved greedily
     * across slots so quantities count) AND none is deferred-craftable — i.e. it genuinely blocks
     * the craft; a slot the engine could fill via intermediates stays quiet, since the tooltip's
     * raw-materials list already tells that story. If every out-of-stock slot is individually
     * craftable yet the recipe still failed (chains competing for the same raws), fall back to
     * stock JEI's semantics — highlight everything out of stock — rather than highlighting nothing.
     */
    private List<Integer> missingSlots(IRecipeLayout recipeLayout, EntityPlayer player,
                                       CraftPlanner.Session session) {
        Map<Integer, ? extends IGuiIngredient<ItemStack>> guiIngredients =
                recipeLayout.getItemStacks().getGuiIngredients();
        Map<ItemKey, Integer> pool = new HashMap<ItemKey, Integer>();
        for (int i = 0; i < 36; i++) {
            accumulate(pool, player.inventory.mainInventory.get(i));
        }
        for (Slot slot : ExternalSlots.materialSlots(player)) {
            accumulate(pool, slot.getStack());
        }
        List<Integer> outOfStock = new ArrayList<Integer>();
        List<Integer> unobtainable = new ArrayList<Integer>();
        for (int jeiSlot = 1; jeiSlot <= 9; jeiSlot++) {
            IGuiIngredient<ItemStack> gui = guiIngredients.get(Integer.valueOf(jeiSlot));
            if (gui == null || !gui.isInput()) {
                continue;
            }
            List<ItemStack> options = gui.getAllIngredients();
            if (options == null || options.isEmpty()) {
                continue;
            }
            boolean stocked = false;
            for (ItemStack option : options) {
                if (option.isEmpty()) {
                    continue;
                }
                ItemKey key = ItemKey.of(option);
                Integer left = pool.get(key);
                if (left != null && left.intValue() > 0) {
                    pool.put(key, Integer.valueOf(left.intValue() - 1));
                    stocked = true;
                    break;
                }
            }
            if (stocked) {
                continue;
            }
            outOfStock.add(Integer.valueOf(jeiSlot));
            boolean craftable = false;
            for (ItemStack option : options) {
                if (!option.isEmpty() && session.canDeferCraft(option)) {
                    craftable = true;
                    break;
                }
            }
            if (!craftable) {
                unobtainable.add(Integer.valueOf(jeiSlot));
            }
        }
        return unobtainable.isEmpty() ? outOfStock : unobtainable;
    }

    private static void accumulate(Map<ItemKey, Integer> pool, ItemStack stack) {
        if (stack.isEmpty() || !CraftExecutor.usableAsIngredient(stack)) {
            return;
        }
        ItemKey key = ItemKey.of(stack);
        Integer current = pool.get(key);
        pool.put(key, Integer.valueOf((current == null ? 0 : current.intValue()) + stack.getCount()));
    }

    /**
     * Fills the crafting grid using Minecraft's drag mechanic: right-drag places 1 per slot
     * (single craft), left-drag distributes evenly (shift-click / max transfer). Ingredients
     * that share the same item across multiple grid positions are grouped into a single drag
     * so they split correctly from one inventory stack.
     */
    private boolean vanillaGridFill(C container, IRecipeLayout recipeLayout, EntityPlayer player,
                                    boolean maxTransfer) {
        // Derive the grid and player-inventory slot indices from the container's slots by type,
        // rather than assuming the vanilla layout (result@0, grid@1..9, player-inv = last 36). The
        // grid cells are the InventoryCrafting-backed slots (in container order = row-major); the
        // source pool is the 36 main player slots (InventoryPlayer indices 0..35, excluding armor
        // and offhand). This makes the drag-fill work for any modded 3x3 station, not just tables.
        List<Integer> gridSlotIndices = new ArrayList<Integer>();
        List<Integer> invSlotIndices = new ArrayList<Integer>();
        int gridWidth = 0;
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot slot = container.getSlot(i);
            IInventory inv = slot.inventory;
            if (inv instanceof InventoryCrafting) {
                gridSlotIndices.add(Integer.valueOf(i));
                gridWidth = ((InventoryCrafting) inv).getWidth();
            } else if (inv instanceof InventoryPlayer && slot.getSlotIndex() < 36) {
                invSlotIndices.add(Integer.valueOf(i));
            }
        }
        int gridSize = gridSlotIndices.size();
        if (gridSize == 0 || gridWidth == 0 || invSlotIndices.isEmpty()) {
            return false;
        }
        int gridHeight = gridSize / gridWidth;

        Minecraft mc = Minecraft.getMinecraft();
        int wid = container.windowId;

        if (!player.inventory.getItemStack().isEmpty()) {
            for (int inv : invSlotIndices) {
                if (!container.getSlot(inv).getHasStack()) {
                    mc.playerController.windowClick(wid, inv, 0, ClickType.PICKUP, player);
                    break;
                }
            }
            if (!player.inventory.getItemStack().isEmpty()) {
                return false;
            }
        }

        for (int gridSlot : gridSlotIndices) {
            if (container.getSlot(gridSlot).getHasStack()) {
                mc.playerController.windowClick(wid, gridSlot, 0, ClickType.QUICK_MOVE, player);
            }
        }

        Map<Integer, ? extends IGuiIngredient<ItemStack>> guiIngredients =
                recipeLayout.getItemStacks().getGuiIngredients();
        // Grouped by full stack identity (item + meta + NBT), not ItemKey: fluid-in-NBT buckets
        // must neither match an empty same-(item, meta) bucket nor merge with a different fluid's
        // bucket into one drag. Templates are copies of the matched INVENTORY stack, so the pick
        // phase below is guaranteed to find them again.
        List<ItemStack> dragTemplates = new ArrayList<ItemStack>();
        List<List<Integer>> dragSlotGroups = new ArrayList<List<Integer>>();

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
            if (jeiCol >= gridWidth || jeiRow >= gridHeight) {
                continue;
            }
            int containerSlot = gridSlotIndices.get(jeiRow * gridWidth + jeiCol).intValue();

            // Pass 1: a stack that matches an option exactly (NBT included). Pass 2: the
            // (item, meta) match against tag-LESS options — a tagged option (filled bucket)
            // must not be satisfied by a stack whose tag differs (the empty bucket) while a
            // better source could exist. Pass 3, only when nothing else matched anywhere: the
            // legacy any-option (item, meta) match — recipes lenient about NBT (vanilla
            // Ingredient.apply ignores tags) crafted fine from such stacks before, and this
            // client-side path has no server verify to recover a refusal, so never do worse
            // than the pre-NBT-aware behavior.
            ItemStack match = ItemStack.EMPTY;
            for (int pass = 0; pass < 3 && match.isEmpty(); pass++) {
                for (int inv : invSlotIndices) {
                    ItemStack inSlot = container.getSlot(inv).getStack();
                    if (inSlot.isEmpty()) {
                        continue;
                    }
                    for (ItemStack option : options) {
                        if (option.isEmpty()) {
                            continue;
                        }
                        boolean hit;
                        if (pass == 0) {
                            hit = sameStackIdentity(inSlot, option);
                        } else if (pass == 1) {
                            hit = !option.hasTagCompound()
                                    && ItemKey.of(inSlot).equals(ItemKey.of(option));
                        } else {
                            hit = ItemKey.of(inSlot).equals(ItemKey.of(option));
                        }
                        if (hit) {
                            match = inSlot.copy();
                            break;
                        }
                    }
                    if (!match.isEmpty()) {
                        break;
                    }
                }
            }

            if (match.isEmpty()) {
                return false;
            }

            List<Integer> slots = null;
            for (int t = 0; t < dragTemplates.size(); t++) {
                if (sameStackIdentity(dragTemplates.get(t), match)) {
                    slots = dragSlotGroups.get(t);
                    break;
                }
            }
            if (slots == null) {
                slots = new ArrayList<Integer>();
                dragTemplates.add(match);
                dragSlotGroups.add(slots);
            }
            slots.add(Integer.valueOf(containerSlot));
        }

        int dragStart = maxTransfer ? 0 : 4;
        int dragAdd   = maxTransfer ? 1 : 5;
        int dragEnd   = maxTransfer ? 2 : 6;

        for (int t = 0; t < dragTemplates.size(); t++) {
            ItemStack template = dragTemplates.get(t);
            List<Integer> gridSlots = dragSlotGroups.get(t);

            for (int inv : invSlotIndices) {
                ItemStack inSlot = container.getSlot(inv).getStack();
                if (inSlot.isEmpty() || !sameStackIdentity(inSlot, template)) {
                    continue;
                }

                // Drag targets: still-fillable cells only, consumed stack by stack until the
                // group is complete. One source stack may not cover the group — unstackable
                // identities (filled buckets, milk buckets) hand a 1-count cursor exactly one
                // drag-slot admission — and a cell that can take no more must not stay a
                // target: it would hog that single admission and starve the rest (and on a
                // single click, deepen a filled cell past the one-per-cell layout). Single
                // click targets EMPTY cells (each ends at exactly one item); shift-fill
                // targets cells below the identity's real stack cap, which for stackables
                // filling evenly from an empty grid is identical to the unfiltered behavior.
                List<Integer> targets = new ArrayList<Integer>();
                for (int gridSlot : gridSlots) {
                    ItemStack inCell = container.getSlot(gridSlot).getStack();
                    boolean fillable = maxTransfer
                            ? inCell.isEmpty() || inCell.getCount() < template.getMaxStackSize()
                            : inCell.isEmpty();
                    if (fillable) {
                        targets.add(Integer.valueOf(gridSlot));
                    }
                }
                if (targets.isEmpty()) {
                    break;
                }

                mc.playerController.windowClick(wid, inv, 0, ClickType.PICKUP, player);
                if (player.inventory.getItemStack().isEmpty()) {
                    continue;
                }

                mc.playerController.windowClick(wid, -999, dragStart, ClickType.QUICK_CRAFT, player);
                for (int gridSlot : targets) {
                    mc.playerController.windowClick(wid, gridSlot, dragAdd, ClickType.QUICK_CRAFT, player);
                }
                mc.playerController.windowClick(wid, -999, dragEnd, ClickType.QUICK_CRAFT, player);

                if (!player.inventory.getItemStack().isEmpty()) {
                    mc.playerController.windowClick(wid, inv, 0, ClickType.PICKUP, player);
                }
            }
        }

        return true;
    }

    /** Same item, same metadata, same NBT — count-independent (the drag-fill's match identity). */
    private static boolean sameStackIdentity(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata()
                && ItemStack.areItemStackTagsEqual(a, b);
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
     * Exact recipe identity straight from the layout's private wrapper (via the
     * {@code RecipeLayoutAccessor} mixin): JEI's vanilla crafting wrappers expose the recipe's
     * registry name through the {@link ICraftingRecipeWrapper} API. O(1) versus the full-registry
     * display-matching scan below, and immune to two recipes sharing a display. Every failure
     * mode degrades to null → the scan: accessor didn't apply (a fork moved the field), a modded
     * wrapper without a registry name, or an older JEI API missing the default method (guarded —
     * SF4 ships 4.15, we compile against 4.16).
     */
    private static IRecipe recipeFromLayout(IRecipeLayout layout) {
        if (!(layout instanceof RecipeLayoutAccessor)) {
            return null;
        }
        try {
            IRecipeWrapper wrapper = ((RecipeLayoutAccessor) layout).sprawlcrafting$getRecipeWrapper();
            if (wrapper instanceof ICraftingRecipeWrapper) {
                ResourceLocation name = ((ICraftingRecipeWrapper) wrapper).getRegistryName();
                if (name != null) {
                    return CraftingManager.getRecipe(name);
                }
            }
        } catch (NoSuchMethodError | AbstractMethodError e) {
            // API too old for getRegistryName — the display-matching scan still works.
        }
        return null;
    }

    /**
     * Identifies the specific recipe shown in the layout by matching its displayed output and
     * inputs against the recipe registry. JEI 4.x does not expose the recipe object through its
     * public API, so we recover it by checking which registered recipe accepts the exact set of
     * items JEI is displaying. Returns null if no match is found (the caller falls back to
     * result-based resolution).
     */
    private static IRecipe findRecipe(IRecipeLayout layout, ItemStack result) {
        IRecipe exact = recipeFromLayout(layout);
        if (exact != null) {
            return exact;
        }
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
