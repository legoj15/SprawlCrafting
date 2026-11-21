package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.solver.SlotAssignment;
import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.client.DeferredClickState;
import com.legoj15.sprawlcrafting.forge.client.PendingGatherScreen;
import com.legoj15.sprawlcrafting.forge.client.ServerPresence;
import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.ExternalSlots;
import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;
import com.legoj15.sprawlcrafting.forge.mixin.jei.RecipeLayoutAccessor;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ItemKey;
import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;
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
import net.minecraft.util.text.TextFormatting;

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
 * container's slots, so it works for any of these container shapes. A recipe makeable only by
 * consuming the player's protected stacks (issue #40) counts as deferred here too: its hover
 * tooltip warns which special items the click would use, and the click sends the protected start.
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
                // Protected-aware (issue #40): a recipe only the protected tier can make is
                // solvable here too, so the click starts it instead of opening the gather list.
                CraftPlanner.Craftability craftability = ClientPlanCache.classify(player, grid, recipe);
                if (craftability == CraftPlanner.Craftability.UNSOLVABLE) {
                    // The orange gather button: "transferring" an unsolvable recipe opens the
                    // missing-resources screen (next tick — see PendingGatherScreen) instead of
                    // asking the engine for a craft it would only reject with a chat error.
                    // Gated live: a click landing after the needs helper was toggled off no-ops.
                    if (SprawlConfig.needsSystem && SprawlConfig.jeiIntegration) {
                        PendingGatherScreen.open(ClientPlanCache.gatherSession(player, grid).shortfall(recipe));
                    }
                    return null;
                }
                if (craftability == CraftPlanner.Craftability.DIRECT) {
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
                if (ClientPlanCache.isProtectedOnly(recipe)) {
                    // The confirmed plan consumed protected stacks (#40): the protected start
                    // packet, only ever sent to a server that advertised the capability.
                    SprawlNetwork.startProtectedByRecipe(recipe.getRegistryName());
                } else {
                    SprawlNetwork.startByRecipe(recipe.getRegistryName());
                }
            } else {
                // Plain only (#40): without a recipe object there is no plan to name the special
                // items in a warning, and consuming them unannounced is exactly what the protected
                // tier must never do. A protected-only result is not offered here.
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
            CraftPlanner.Craftability craftability = ClientPlanCache.classify(player, grid, recipe);
            if (craftability != CraftPlanner.Craftability.UNSOLVABLE) {
                // Solvable → no error object exists to carry state, so the button tint (yellow =
                // deferred multi-step, plain = direct move) rides the side-band cache instead.
                JeiTintState.put(recipeLayout, craftability != CraftPlanner.Craftability.DIRECT
                        ? JeiTintState.Tint.DEFERRED : null);
                // A protected-only recipe's hover tooltip says which special items the click would
                // consume (#40); for every other solvable recipe there is nothing to warn about.
                return ClientPlanCache.isProtectedOnly(recipe)
                        ? new ProtectedDeferredError(grid, recipe) : null;
            }
            if (!SprawlConfig.needsSystem) {
                JeiTintState.put(recipeLayout, null);
                return helper.createUserErrorWithTooltip(
                        I18n.format("jei.tooltip.error.recipe.transfer.missing"));
            }
            JeiTintState.put(recipeLayout, JeiTintState.Tint.GATHER);
            // List and red slots over the same pool (#48): protected stacks count while the tier is on.
            CraftPlanner.Session gather = ClientPlanCache.gatherSession(player, grid);
            return new MissingResourcesError(gather.shortfall(recipe),
                    missingSlots(recipeLayout, player, gather, ServerPresence.protectedCraft()));
        }
        if (session.canDeferCraft(result)) { // plain only: see the click path (#40)
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
                missingSlots(recipeLayout, player, session, false));
    }

    /**
     * The yellow state for a recipe only the protected tier can make (issue #40): the deferred
     * invite, plus the special-items warning in place of the gather list. An
     * {@link mezz.jei.api.recipe.transfer.IRecipeTransferError} is the only tooltip hook JEI 4
     * offers, and JEI greys out every errored button — so this extends
     * {@link MissingResourcesError} (whose type {@code RecipeTransferButtonMixin} re-enables) to
     * stay clickable while carrying its own hover text. The warning lines are recomputed on every
     * hover, as legacy-1.16.5's own ProtectedDeferredError does: JEI may keep the error object
     * across inventory changes, so lines built once (at validation) would go stale and name items
     * the live pool no longer needs. One plan per hover against the cached client session; the
     * click re-plans authoritatively on the server anyway.
     */
    private static final class ProtectedDeferredError extends MissingResourcesError {

        private final GridContext grid;
        private final IRecipe recipe;

        ProtectedDeferredError(GridContext grid, IRecipe recipe) {
            // Never read: showError below draws this class's own tooltip instead, with no red
            // slot highlights — nothing is "missing", the recipe just needs the player's consent.
            super(ShortfallView.unavailable(), Collections.<Integer>emptyList());
            this.grid = grid;
            this.recipe = recipe;
        }

        @Override
        public void showError(Minecraft minecraft, int mouseX, int mouseY,
                              IRecipeLayout recipeLayout, int recipeX, int recipeY) {
            if (minecraft.currentScreen != null) {
                minecraft.currentScreen.drawHoveringText(tooltip(minecraft), mouseX, mouseY);
            }
        }

        /** The deferred invite plus the current special-items warning, planned at draw time. */
        private List<String> tooltip(Minecraft minecraft) {
            List<String> lines = new ArrayList<String>();
            lines.add(TextFormatting.YELLOW + I18n.format("sprawlcrafting.recipe.deferred"));
            lines.add(TextFormatting.GRAY + I18n.format("sprawlcrafting.recipe.deferred.click"));
            if (minecraft.player != null) {
                try {
                    CraftPlanner.PlanOutcome outcome = ClientPlanCache.plan(minecraft.player, grid, recipe);
                    if (outcome instanceof CraftPlanner.PlanOutcome.Planned) {
                        lines.addAll(DeferredClickState.protectedWarningLines(
                                ((CraftPlanner.PlanOutcome.Planned) outcome).job(), minecraft.player, grid));
                    }
                } catch (Throwable ignored) {
                    // The warning is advisory; the click re-plans server-side regardless.
                }
            }
            return lines;
        }
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
     * {@code includeProtected} counts the 36 main slots' protected stacks (#40) and — while the
     * server also counts them (#49's capability) — an open terminal network's, as the planner's
     * protected pool does; pass it with that pool's session so slots and gather list agree (#48).
     */
    private List<Integer> missingSlots(IRecipeLayout recipeLayout, EntityPlayer player,
                                       CraftPlanner.Session session, boolean includeProtected) {
        Map<Integer, ? extends IGuiIngredient<ItemStack>> guiIngredients =
                recipeLayout.getItemStacks().getGuiIngredients();
        Map<ItemKey, Integer> pool = new HashMap<ItemKey, Integer>();
        for (int i = 0; i < 36; i++) {
            accumulate(pool, player.inventory.mainInventory.get(i), includeProtected);
        }
        // The grid's protected stacks count as the planner's protected pool counts them (#50):
        // only when the confirming clear would move the grid; a station's chest stays plain.
        boolean gridProtected = includeProtected && CraftPlanner.gridWouldClear(player);
        for (Slot slot : ExternalSlots.materialSlots(player)) {
            accumulate(pool, slot.getStack(), gridProtected && ExternalSlots.isMatrix(slot));
        }
        // An open AE2 Crafting Terminal's network is part of the solver's pool, so it is part of
        // this one; its protected identities count only with the caller's protected pool AND the
        // server's network-protected capability (issue #49), the same gate the planner's
        // protected session uses.
        boolean networkProtected = includeProtected && ServerPresence.networkProtected();
        for (ItemStack stack : NetworkTerminals.stacks(player)) {
            accumulate(pool, stack, networkProtected);
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

    private static void accumulate(Map<ItemKey, Integer> pool, ItemStack stack, boolean includeProtected) {
        if (stack.isEmpty() || !CraftExecutor.usableAsIngredient(stack, includeProtected)) {
            return;
        }
        ItemKey key = ItemKey.of(stack);
        Integer current = pool.get(key);
        pool.put(key, Integer.valueOf(NetworkTerminals.saturatedAdd(
                current == null ? 0 : current.intValue(), stack.getCount())));
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
        // bucket into one drag. Templates are copies of the matched INVENTORY stack, so the drag
        // phase below is guaranteed to find them again.
        List<ItemStack> dragTemplates = new ArrayList<ItemStack>();
        List<List<Integer>> dragSlotGroups = new ArrayList<List<Integer>>();
        // The inventory's distinct usable stack identities (item + meta + NBT) and how many of each
        // it holds. Damaged, enchanted and renamed stacks are left out, as the planner leaves them
        // out: they differ in meta or NBT, so the drag below never touches them either.
        List<ItemStack> identities = new ArrayList<ItemStack>();
        Map<Integer, Integer> stock = new HashMap<Integer, Integer>();
        for (int inv : invSlotIndices) {
            ItemStack inSlot = container.getSlot(inv).getStack();
            if (inSlot.isEmpty() || !CraftExecutor.usableAsIngredient(inSlot)) {
                continue;
            }
            int id = identities.size();
            for (int i = 0; i < identities.size(); i++) {
                if (sameStackIdentity(identities.get(i), inSlot)) {
                    id = i;
                    break;
                }
            }
            if (id == identities.size()) {
                identities.add(inSlot.copy());
            }
            Integer have = stock.get(Integer.valueOf(id));
            stock.put(Integer.valueOf(id), Integer.valueOf((have == null ? 0 : have.intValue()) + inSlot.getCount()));
        }

        // Each cell's acceptable identities in preference order. Exact matches (NBT included)
        // first, then (item, meta) matches against tag-LESS options — a tagged option (filled
        // bucket) is never satisfied by a stack whose tag differs (the empty bucket). Only for a
        // cell with neither: the legacy any-option (item, meta) match — recipes lenient about NBT
        // (vanilla Ingredient.apply ignores tags) crafted fine from such stacks before, and this
        // client-side path has no server verify to recover a refusal, so never do worse than the
        // pre-NBT-aware behavior.
        List<Integer> cellSlots = new ArrayList<Integer>();
        List<List<Integer>> cellAlternatives = new ArrayList<List<Integer>>();
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
            List<Integer> alternatives = new ArrayList<Integer>();
            addAlternatives(alternatives, 0, options, identities);
            addAlternatives(alternatives, 1, options, identities);
            if (alternatives.isEmpty()) {
                addAlternatives(alternatives, 2, options, identities);
            }
            if (alternatives.isEmpty()) {
                return false;
            }
            cellSlots.add(gridSlotIndices.get(jeiRow * gridWidth + jeiCol));
            cellAlternatives.add(alternatives);
        }

        // Which identity fills which cell is the planner's own rule (SlotAssignment: most
        // constrained cell first, non-scarce alternative, augmenting re-route), so no two cells
        // count on one lone stack and a layout is found whenever one exists. No full layout
        // means the caller hands the craft to the engine (on a server without the mod: its
        // missing-mod notice; the grid was already swept into the inventory, so nothing is lost).
        List<Integer> chosen = SlotAssignment.assign(cellAlternatives, stock);
        for (int c = 0; c < cellSlots.size(); c++) {
            if (chosen.get(c) == null) {
                return false;
            }
            int containerSlot = cellSlots.get(c).intValue();
            ItemStack match = identities.get(chosen.get(c).intValue());

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
                int held = player.inventory.getItemStack().getCount();
                if (held == 0) {
                    continue;
                }
                // Vanilla's drag admits a slot only while the cursor holds more items than slots
                // already admitted (issue #38), in the order offered, so a short cursor (1-count
                // stacks) must be offered the emptiest cells first: otherwise the first cell keeps
                // winning and the group piles into it. No explicit cap: vanilla stops at the held
                // count itself, and a cap would waste admissions on a cell a modded grid refuses.
                if (maxTransfer) {
                    Collections.sort(targets, new Comparator<Integer>() {
                        @Override
                        public int compare(Integer a, Integer b) {
                            return container.getSlot(a.intValue()).getStack().getCount()
                                    - container.getSlot(b.intValue()).getStack().getCount();
                        }
                    });
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

        // Verify the fill (issue #38): every planned cell must hold its chosen identity. This reads
        // the client's prediction, so it catches a cell the sweep could not clear or a slot the drag
        // refused, not a server-side trim (the server resyncs the window itself on a mismatch). A
        // failed cell would otherwise pass as placed and skip the engine fallback; on a mismatch,
        // return the grid and let the caller hand off.
        for (int c = 0; c < cellSlots.size(); c++) {
            ItemStack inCell = container.getSlot(cellSlots.get(c).intValue()).getStack();
            if (inCell.isEmpty() || !sameStackIdentity(inCell, identities.get(chosen.get(c).intValue()))) {
                for (int gridSlot : gridSlotIndices) {
                    if (container.getSlot(gridSlot).getHasStack()) {
                        mc.playerController.windowClick(wid, gridSlot, 0, ClickType.QUICK_MOVE, player);
                    }
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Appends to {@code alternatives}, in the recipe's option order (the planner's preference
     * order), every identity not yet listed that matches an option under {@code pass}: 0 exact,
     * 1 (item, meta) against a tag-less option, 2 legacy (item, meta) against any option.
     */
    private static void addAlternatives(List<Integer> alternatives, int pass, List<ItemStack> options,
                                        List<ItemStack> identities) {
        for (ItemStack option : options) {
            if (option.isEmpty()) {
                continue;
            }
            for (int i = 0; i < identities.size(); i++) {
                ItemStack identity = identities.get(i);
                boolean hit;
                if (pass == 0) {
                    hit = sameStackIdentity(identity, option);
                } else if (pass == 1) {
                    hit = !option.hasTagCompound() && ItemKey.of(identity).equals(ItemKey.of(option));
                } else {
                    hit = ItemKey.of(identity).equals(ItemKey.of(option));
                }
                if (hit && !alternatives.contains(Integer.valueOf(i))) {
                    alternatives.add(Integer.valueOf(i));
                }
            }
        }
    }

    /** Same item, same metadata, same NBT — count-independent (the drag-fill's match identity). */
    private static boolean sameStackIdentity(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata()
                && ItemStack.areItemStackTagsEqual(a, b);
    }

    /** The crafting category's output is GUI ingredient slot 0; fall back to the first output slot. */
    static ItemStack outputStack(IRecipeLayout layout) {
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
    static IRecipe findRecipe(IRecipeLayout layout, ItemStack result) {
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
