package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;

import com.legoj15.solver.LedgerTally;
import com.legoj15.solver.ProducedLedger;
import com.legoj15.solver.SlotAssignment;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.crafting.IShapedRecipe;

/**
 * Performs a single craft of a recipe directly against a player's live inventory: consumes one
 * item per ingredient slot, returns crafting remainders (empty buckets), and inserts the result
 * as real items (overflow drops at the player's feet). Java-8 port of the modern executor.
 *
 * <p>This is the per-step consumption point of DESIGN.md: ingredients are re-checked against
 * reality here, every half second — the plan only predicted them.
 *
 * <p>Invariants shared with {@link CraftPlanner}:
 * <ul>
 *   <li>Ingredients are consumed from the same pool the planner counted: the 36 main inventory
 *       slots and, when a recognised crafting station is open, its connected side inventory (a
 *       Tinkers' Construct Crafting Station's bound chest — see {@link ExternalSlots}). Armor and
 *       offhand are never touched. Results and crafting remainders are always inserted into the 36
 *       main slots, never the chest: consume-from-chest, deposit-to-player is safe and matches
 *       intuition. With a network crafting terminal open ({@link NetworkTerminals}, the AE2 Crafting
 *       Terminal) the network is a third pool, used after the player's slots; network picks are
 *       extracted all-or-nothing before anything is consumed, and only overflow that would otherwise
 *       drop at the player's feet is inserted into the network.</li>
 *   <li>Damaged, enchanted, or renamed stacks are never consumed, matching what the vanilla
 *       recipe book refuses to auto-fill, except up to what the job's {@link ProducedLedger}
 *       says it produced itself (some mods enchant an item on its first inventory tick, after
 *       the job inserted it).</li>
 * </ul>
 *
 * <p>The final craft of a job is normally handed off into an open crafting grid by
 * {@link ServerGridPlacer} (see {@code CraftQueueManager}); this executor is the fallback that
 * auto-crafts it — and every intermediate — directly into the inventory.
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    private static final int MAIN = CraftPlanner.MAIN_INVENTORY_SIZE;

    public abstract static class CraftResult {
        private CraftResult() {
        }

        /**
         * {@code crafted} is a display copy of what was produced. {@code usedChest} / {@code usedNetwork}
         * say whether this craft actually consumed from a station's connected chest / a network
         * terminal's network (or, for the network, stored overflow in it), so the job learns it depends on that source even if it was not visible
         * when the job started.
         */
        public static final class Success extends CraftResult {
            private final ItemStack crafted;
            private final boolean usedChest;
            private final boolean usedNetwork;

            public Success(ItemStack crafted, boolean usedChest, boolean usedNetwork) {
                this.crafted = crafted;
                this.usedChest = usedChest;
                this.usedNetwork = usedNetwork;
            }

            public ItemStack crafted() {
                return crafted;
            }

            public boolean usedChest() {
                return usedChest;
            }

            public boolean usedNetwork() {
                return usedNetwork;
            }
        }

        /** An ingredient vanished since planning; {@code missing} names it for the player. */
        public static final class MissingIngredient extends CraftResult {
            private final String missing;

            public MissingIngredient(String missing) {
                this.missing = missing;
            }

            public String missing() {
                return missing;
            }
        }

        /** Recipe no longer exists or has no fixed result. */
        public static final class RecipeGone extends CraftResult {
        }
    }

    public static CraftResult craftOnce(EntityPlayerMP player, ResourceLocation recipeId,
                                        ProducedLedger<ItemKey> ledger) {
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1: match every ingredient slot to a source without mutating, so a shortfall
        // discovered at slot N leaves earlier matches — and the chest — untouched. chosen keeps
        // one entry per ingredient slot (-1 for a gap in the shape) so the simulation below can
        // reconstruct the grid positions.
        InventoryPlayer inventory = player.inventory;
        Assignment assignment = assign(player, recipe.getIngredients(), ledger, true);
        if (assignment.missing != null) {
            return new CraftResult.MissingIngredient(describe(assignment.missing));
        }
        List<Source> sources = assignment.sources;
        int[] chosen = assignment.chosen;

        // What this craft actually produces, asked of the recipe with the exact stacks about to
        // be consumed: getRecipeOutput() alone is NBT-less for input-dependent outputs and, per
        // its contract, empty for dynamic recipes — which the old early-exit misreported as
        // RecipeGone before the ingredients were even checked.
        ItemStack result = computeResult(recipe, sources, chosen);
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1b: pull every network item this craft needs out of the network up front, all or
        // nothing, so the network can no longer come up short once phase 2 starts consuming (a
        // network extraction can fail — power, a concurrent export bus — where the player's own slots,
        // decremented in place, cannot). If the network hands over less than was counted, everything
        // it did hand over goes straight back and the craft reports the item as missing.
        ItemStack networkShort = prepareNetwork(player, sources, chosen);
        if (!networkShort.isEmpty()) {
            return new CraftResult.MissingIngredient(networkShort.getDisplayName());
        }

        // Phase 2: apply — consume, collect remainders, insert the result into the player only. It
        // is not failure-free (a modded item's getContainerItem or an insert can throw), so anything
        // taken from the network and not yet consumed is put back, and remainders already collected
        // (empty buckets) go to the player, before the error propagates.
        List<ItemStack> remainders = new ArrayList<ItemStack>();
        try {
            boolean touchedExternal = false;
            boolean usedChest = false;
            boolean usedNetwork = false;
            for (int idx : chosen) {
                if (idx < 0) {
                    continue;
                }
                Source source = sources.get(idx);
                // One item, as in a real grid cell: peek() of a network source is the whole network's
                // count, and a mod may read (or mutate) the stack it is asked about.
                ItemStack one = source.peek().copy();
                one.setCount(1);
                Item item = one.getItem();
                if (item.hasContainerItem(one)) {
                    ItemStack remainder = item.getContainerItem(one);
                    if (!remainder.isEmpty()) {
                        remainders.add(remainder);
                    }
                }
                source.consumeOne();
                touchedExternal |= source.isExternal();
                usedChest |= source.isChest();
                usedNetwork |= source instanceof NetworkSource;
            }
            assignment.chargeTo(ledger);
            // Removed before inserting, so the failure path never hands one out twice.
            while (!remainders.isEmpty()) {
                usedNetwork |= insertIntoMainInventory(player, remainders.remove(0));
            }
            ItemStack crafted = result.copy();
            // Overflow stored in the network counts as reliance too: closing the terminal must then
            // pause the job (its item now sits in the network), not cancel it.
            usedNetwork |= insertIntoMainInventory(player, result);
            ledger.produced(ledgerKey(crafted), crafted.getCount());
            inventory.markDirty();
            if (touchedExternal) {
                // Push the depleted chest back to any open station GUI viewing it (the same sync
                // clearOpenCraftingGrid performs after it moves grid contents).
                player.openContainer.detectAndSendChanges();
            }
            return new CraftResult.Success(crafted, usedChest, usedNetwork);
        } catch (Throwable failure) {
            returnPreparedNetworkItems(player, sources, failure);
            for (ItemStack remainder : remainders) {
                try {
                    insertIntoMainInventory(player, remainder);
                } catch (Throwable refundFailure) {
                    failure.addSuppressed(refundFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Puts every network item {@link #prepareNetwork} extracted and phase 2 had not consumed yet back
     * into the network (what it refuses goes to the player, see {@link NetworkSource#refund}). Runs on
     * the failure path only, so a failing refund is attached to {@code failure} as suppressed instead
     * of replacing it, and never stops the other sources from being returned. Items phase 2 already
     * consumed (decremented) before the failure are not restored.
     */
    private static void returnPreparedNetworkItems(EntityPlayer player, Iterable<? extends Source> sources,
                                                   Throwable failure) {
        for (Source source : sources) {
            if (source instanceof NetworkSource) {
                try {
                    ((NetworkSource) source).refund(player);
                } catch (Throwable refundFailure) {
                    failure.addSuppressed(refundFailure);
                }
            }
        }
    }

    /**
     * The stack this craft produces, always a private copy safe to mutate. Asks
     * {@link IRecipe#getCraftingResult} against a simulated grid holding the exact stacks phase 1
     * matched, so outputs derived from the inputs (NBT-copying recipes, recipes returning filled
     * containers) come out right; {@link IRecipe#getRecipeOutput} is only the fallback, for
     * recipes whose declared shape doesn't fit a simulated grid.
     */
    private static ItemStack computeResult(IRecipe recipe, List<Source> sources, int[] chosen) {
        ItemStack simulated = resultFromSimulatedGrid(recipe, sources, chosen);
        return simulated.isEmpty() ? recipe.getRecipeOutput().copy() : simulated;
    }

    /**
     * Builds the crafting grid this consumption is equivalent to — one copy of each matched stack,
     * a shaped recipe's ingredients anchored top-left exactly as {@code ShapedRecipes.checkMatch}
     * reads them, shapeless laid out row-major — and asks the recipe for its result. The grid is
     * never smaller than 3x3 so recipe classes written against a real crafting table see the
     * geometry they expect. Empty on any failure: the caller falls back to the declared output,
     * which is exactly the pre-simulation behavior.
     */
    private static ItemStack resultFromSimulatedGrid(IRecipe recipe, List<Source> sources, int[] chosen) {
        int recipeWidth;
        if (recipe instanceof IShapedRecipe) {
            recipeWidth = ((IShapedRecipe) recipe).getRecipeWidth();
            int recipeHeight = ((IShapedRecipe) recipe).getRecipeHeight();
            if (recipeWidth <= 0 || recipeHeight <= 0 || recipeWidth * recipeHeight < chosen.length) {
                return ItemStack.EMPTY;
            }
        } else {
            recipeWidth = 3;
        }
        int width = Math.max(3, recipeWidth);
        int height = Math.max(3, (chosen.length + recipeWidth - 1) / recipeWidth);
        InventoryCrafting grid = new InventoryCrafting(SIMULATION_CONTAINER, width, height);
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i] < 0) {
                continue;
            }
            ItemStack one = sources.get(chosen[i]).peek().copy();
            one.setCount(1);
            grid.setInventorySlotContents((i / recipeWidth) * width + (i % recipeWidth), one);
        }
        try {
            ItemStack result = recipe.getCraftingResult(grid);
            return result.isEmpty() ? ItemStack.EMPTY : result.copy();
        } catch (RuntimeException e) {
            // A modded recipe choked on a grid it never expected to see outside its own
            // container; a wrong-but-present output beats crashing the craft tick.
            return ItemStack.EMPTY;
        }
    }

    /**
     * {@link InventoryCrafting} notifies its owning container on every slot write; the simulated
     * grid has no real container, so the notification is swallowed (the base implementation would
     * attempt a client sync of a container nobody is viewing).
     */
    private static final Container SIMULATION_CONTAINER = new Container() {
        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return false;
        }

        @Override
        public void onCraftMatrixChanged(IInventory inventory) {
        }
    };

    /**
     * Non-mutating: whether {@code recipe} can be crafted once from the player's 36 main slots
     * alone (ignoring any connected station inventory). Lets the recipe-book click decide whether a
     * DIRECT recipe can fall through to vanilla placement (player has everything) or must be routed
     * through the engine because it depends on the station's connected chest. Runs on either side.
     */
    public static boolean canCraftFromPlayerInventory(EntityPlayer player, IRecipe recipe) {
        List<Source> sources = new ArrayList<Source>();
        for (int i = 0; i < MAIN; i++) {
            sources.add(new PlayerSource(player.inventory, i));
        }
        return canCraftFrom(sources, recipe.getIngredients());
    }

    /**
     * Non-mutating: whether {@code recipe} can be crafted once from the 36 main slots plus the
     * open container's material slots (its grid leftovers and any station chest) — the same pool
     * the solver classified with, and a subset of what {@code ServerGridPlacer} counts, so a
     * DIRECT classification and this gate can never disagree. Gates the JEI "+" server-placement
     * path, so a recipe direct only thanks to the chest is staged into the grid (stock
     * TConstruct+JEI behavior) rather than routed through the engine.
     */
    public static boolean canCraftFromPlayerAndStation(EntityPlayer player, IRecipe recipe) {
        return canCraftFrom(buildSources(player, player.inventory, null), recipe.getIngredients());
    }

    /**
     * Whether one craft can be laid out from {@code sources}, choosing stacks exactly as
     * {@link #assign} does ({@link #preferredKeys} first, then first match, no ledger), so a gate
     * that says yes is never contradicted by the assignment that follows. The converse holds only
     * without a ledger: a ledger lets assign also take admitted altered stacks the gate ignores,
     * which is the safe direction (a no routes the craft to the engine).
     */
    private static boolean canCraftFrom(List<Source> sources, List<Ingredient> ingredients) {
        ItemKey[] preferred = preferredKeys(sources, ingredients);
        int[] reserved = new int[sources.size()];
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient == Ingredient.EMPTY) {
                continue;
            }
            int idx = findMatchingSource(sources, ingredient, preferred[i], reserved, null, null);
            if (idx < 0) {
                return false;
            }
            reserved[idx]++;
        }
        return true;
    }

    /**
     * The ordered consumable pool: the 36 main slots first, then the open container's other input
     * slots (its crafting grid and any station side inventory), then — with a network crafting
     * terminal open and {@code networkItems} non-null — one source per stored network identity of
     * those items ({@link NetworkTerminals}). The grid is normally swept into the inventory by
     * {@link #clearOpenCraftingGrid} before a job starts, so it is usually empty here; including it
     * makes execution match planning even when that sweep was a no-op (inventory full), rather than
     * stalling on a recipe the highlight showed as craftable. The network comes last so the player's
     * own items are always used first, and only the identities the recipe can use are listed (a
     * network can hold thousands, and this runs once per craft of a burst).
     */
    private static List<Source> buildSources(EntityPlayer player, InventoryPlayer inventory, Set<Item> networkItems) {
        List<Source> sources = new ArrayList<Source>();
        for (int i = 0; i < MAIN; i++) {
            sources.add(new PlayerSource(inventory, i));
        }
        for (Slot slot : ExternalSlots.materialSlots(player)) {
            sources.add(new ExternalSource(slot));
        }
        if (networkItems != null && !networkItems.isEmpty() && !player.world.isRemote) {
            NetworkTerminals.View network = NetworkTerminals.view(player);
            if (network != null) {
                for (ItemStack stack : network.stacks(networkItems)) {
                    sources.add(new NetworkSource(network, stack));
                }
            }
        }
        return sources;
    }

    /** Every item any of {@code ingredients} can match (Ingredient expands wildcards to concrete stacks). */
    private static Set<Item> ingredientItems(List<Ingredient> ingredients) {
        Set<Item> items = new HashSet<Item>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient == Ingredient.EMPTY) {
                continue;
            }
            for (ItemStack option : ingredient.getMatchingStacks()) {
                if (!option.isEmpty()) {
                    items.add(option.getItem());
                }
            }
        }
        return items;
    }

    /**
     * Extracts, ahead of phase 2, exactly the number of items phase 1 assigned to each network
     * source. Simulates them all first (no side effects on a shortfall), then extracts for real; if a
     * real extraction still comes up short (power or a concurrent export between the two calls),
     * every item already extracted goes back into the network — or, should the network refuse it,
     * into the player's inventory, never the void. Returns EMPTY on success, else a stack naming the
     * short item.
     */
    private static ItemStack prepareNetwork(EntityPlayer player, List<Source> sources, int[] chosen) {
        Map<NetworkSource, Integer> needed = null;
        for (int idx : chosen) {
            if (idx >= 0 && sources.get(idx) instanceof NetworkSource) {
                if (needed == null) {
                    needed = new LinkedHashMap<NetworkSource, Integer>();
                }
                NetworkSource source = (NetworkSource) sources.get(idx);
                Integer have = needed.get(source);
                needed.put(source, (have == null ? 0 : have) + 1);
            }
        }
        if (needed == null) {
            return ItemStack.EMPTY;
        }
        for (Map.Entry<NetworkSource, Integer> entry : needed.entrySet()) {
            NetworkSource source = entry.getKey();
            if (source.network.extract(source.template, entry.getValue(), true) < entry.getValue()) {
                return source.template;
            }
        }
        List<NetworkSource> prepared = new ArrayList<NetworkSource>();
        try {
            for (Map.Entry<NetworkSource, Integer> entry : needed.entrySet()) {
                NetworkSource source = entry.getKey();
                int want = entry.getValue();
                int got = source.network.extract(source.template, want, false);
                source.prepared = got;
                prepared.add(source);
                if (got < want) {
                    for (NetworkSource undo : prepared) {
                        undo.refund(player);
                    }
                    return source.template;
                }
            }
        } catch (Throwable failure) {
            // A later extraction threw after earlier ones succeeded: those items go back too.
            returnPreparedNetworkItems(player, prepared, failure);
            throw failure;
        }
        return ItemStack.EMPTY;
    }

    /**
     * One source per ingredient ({@code -1} for a gap: null or {@link Ingredient#EMPTY}) over the
     * pool {@link #buildSources} describes, chosen without mutating anything, or the first
     * ingredient nothing could satisfy. A stack {@link #usableAsIngredient} rejects is chosen only
     * when no acceptable stack matches and {@code ledger} (null: never) still admits its identity
     * through {@link LedgerTally#admissible}: only rejected copies above those the player held
     * before the job produced anything. Every pick is recorded in the tally, which
     * {@link Assignment#chargeTo} charges: rejected picks, plus plain picks beyond the job's
     * rejected copies of that identity the pool still
     * holds. Package-visible: the final-step hand-off ({@link ServerGridPlacer}) lays out the same
     * choice.
     */
    static Assignment assign(EntityPlayer player, List<Ingredient> ingredients, ProducedLedger<ItemKey> ledger) {
        return assign(player, ingredients, ledger, false);
    }

    /**
     * {@link #assign(EntityPlayer, List, ProducedLedger)} over a pool that, with {@code withNetwork},
     * also holds the open network terminal's stock. Only {@link #craftOnce} passes true: it is the one
     * consumer that {@link #prepareNetwork extracts} its network picks before taking them. The grid
     * placer's {@link Assignment#take} never sees a network source.
     */
    private static Assignment assign(EntityPlayer player, List<Ingredient> ingredients,
                                     ProducedLedger<ItemKey> ledger, boolean withNetwork) {
        List<Source> sources = buildSources(player, player.inventory,
                withNetwork ? ingredientItems(ingredients) : null);
        int[] chosen = new int[ingredients.size()];
        int[] reserved = new int[sources.size()];
        LedgerTally<ItemKey> tally = new LedgerTally<ItemKey>();
        Map<ItemKey, Integer> ledgerTakes = tally.refusedTakes();
        ProducedLedger<ItemKey> admitting = null;
        if (ledger != null) {
            countRefused(sources, tally);
            admitting = tally.admissible(ledger);
        }
        ItemKey[] preferred = preferredKeys(sources, ingredients);
        for (int i = 0; i < chosen.length; i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient == Ingredient.EMPTY) {
                chosen[i] = -1;
                continue;
            }
            int idx = findMatchingSource(sources, ingredient, preferred[i], reserved, admitting, ledgerTakes);
            if (idx < 0) {
                return new Assignment(sources, chosen, tally, ingredient);
            }
            reserved[idx]++;
            chosen[i] = idx;
            if (ledger != null) {
                ItemStack stack = sources.get(idx).peek();
                tally.picked(ledgerKey(stack), !usableAsIngredient(stack));
            }
        }
        return new Assignment(sources, chosen, tally, null);
    }

    /** Records in {@code tally}, by {@link #ledgerKey}, every stack in the pool that {@link #usableAsIngredient} rejects. */
    private static void countRefused(List<Source> sources, LedgerTally<ItemKey> tally) {
        // Summed in a long and saturated per key before the tally sees it: a network source's peek is
        // the network's whole count (up to Integer.MAX_VALUE), and an int overflow would flip held
        // negative and admit the player's own altered copies. Over-counting held is the safe side.
        Map<ItemKey, Long> refused = new LinkedHashMap<ItemKey, Long>();
        for (Source source : sources) {
            ItemStack stack = source.peek();
            if (!stack.isEmpty() && !usableAsIngredient(stack)) {
                ItemKey key = ledgerKey(stack);
                Long sum = refused.get(key);
                refused.put(key, (sum == null ? 0L : sum) + stack.getCount());
            }
        }
        for (Map.Entry<ItemKey, Long> entry : refused.entrySet()) {
            tally.held(entry.getKey(), (int) Math.min(entry.getValue(), Integer.MAX_VALUE));
        }
    }

    static final class Assignment {
        private final List<Source> sources;
        private final int[] chosen;
        private final LedgerTally<ItemKey> tally;
        private final Ingredient missing;

        private Assignment(List<Source> sources, int[] chosen, LedgerTally<ItemKey> tally,
                           Ingredient missing) {
            this.sources = sources;
            this.chosen = chosen;
            this.tally = tally;
            this.missing = missing;
        }

        boolean complete() {
            return missing == null;
        }

        /** Removes and returns one item for ingredient {@code index}; EMPTY for a gap. */
        ItemStack take(int index) {
            if (chosen[index] < 0) {
                return ItemStack.EMPTY;
            }
            Source source = sources.get(chosen[index]);
            ItemStack one = source.peek().copy();
            one.setCount(1);
            source.consumeOne();
            return one;
        }

        void chargeTo(ProducedLedger<ItemKey> ledger) {
            tally.chargeTo(ledger);
        }
    }

    /**
     * The ledger's identity for a stack: item plus metadata only for subtyped items that are not
     * damageable, because a damageable item's metadata is its damage, which the alteration may
     * change.
     */
    static ItemKey ledgerKey(ItemStack stack) {
        boolean subtyped = stack.getHasSubtypes() && !stack.getItem().isDamageable();
        return new ItemKey(stack.getItem(), subtyped ? stack.getMetadata() : 0);
    }

    /**
     * Per ingredient, the identity {@link SlotAssignment} picks for it from the usable stacks of the
     * whole pool (null for a gap or when nothing usable fits). The solver plans every craft with the
     * same rule over the same slot lists ({@link CraftPlanner#slotAlternatives}) and the same pool
     * counts, so each craft takes what the plan counted on, not whichever stack comes first.
     */
    private static ItemKey[] preferredKeys(List<Source> sources, List<Ingredient> ingredients) {
        Map<ItemKey, Integer> stock = new HashMap<ItemKey, Integer>();
        for (Source source : sources) {
            ItemStack stack = source.peek();
            if (!stack.isEmpty() && usableAsIngredient(stack)) {
                ItemKey key = ItemKey.of(stack);
                Integer have = stock.get(key);
                stock.put(key, NetworkTerminals.saturatedAdd(have == null ? 0 : have, stack.getCount()));
            }
        }
        List<List<ItemKey>> cells = new ArrayList<List<ItemKey>>();
        for (Ingredient ingredient : ingredients) {
            cells.add(ingredient == null || ingredient == Ingredient.EMPTY
                    ? null : CraftPlanner.slotAlternatives(ingredient));
        }
        return SlotAssignment.assignCells(cells, stock).toArray(new ItemKey[0]);
    }

    /**
     * A stack of {@code preferred} ({@link #preferredKeys}) when one matches; otherwise the first
     * match in pool order, then (with a ledger) a refused stack the ledger admits.
     */
    private static int findMatchingSource(List<Source> sources, Ingredient ingredient, ItemKey preferred,
                                          int[] reserved, ProducedLedger<ItemKey> ledger,
                                          Map<ItemKey, Integer> ledgerTakes) {
        if (preferred != null) {
            for (int i = 0; i < sources.size(); i++) {
                ItemStack stack = sources.get(i).peek();
                if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && preferred.equals(ItemKey.of(stack))
                        && usableAsIngredient(stack) && ingredient.apply(stack)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).peek();
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.apply(stack)) {
                return i;
            }
        }
        if (ledger == null) {
            return -1;
        }
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).peek();
            if (stack.isEmpty() || stack.getCount() - reserved[i] <= 0 || usableAsIngredient(stack)) {
                continue;
            }
            ItemKey key = ledgerKey(stack);
            Integer taken = ledgerTakes.get(key);
            if (ledger.canTake(key, taken == null ? 0 : taken) && ingredient.apply(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A single consumable stack location, unifying the player's main slots and a station's
     * side-inventory slots behind one interface so the matcher and a single {@code reserved} ledger
     * span both pools. The chest is consumed through {@link Slot#decrStackSize} (which write-throughs
     * to the backing item handler and respects any wrapper the station layered on); a main slot is
     * mutated in place as before.
     */
    private interface Source {
        /** Current contents (read-only; may be a copy for handler-backed slots). */
        ItemStack peek();

        /** Remove one item. Never called more times than {@link #peek}'s count, per the ledger. */
        void consumeOne();

        boolean isExternal();

        /** A station's connected side inventory (its chest), as opposed to the crafting grid or the player. */
        boolean isChest();
    }

    private static final class PlayerSource implements Source {
        private final InventoryPlayer inventory;
        private final int slot;

        PlayerSource(InventoryPlayer inventory, int slot) {
            this.inventory = inventory;
            this.slot = slot;
        }

        @Override
        public ItemStack peek() {
            return inventory.mainInventory.get(slot);
        }

        @Override
        public void consumeOne() {
            ItemStack stack = inventory.mainInventory.get(slot);
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.mainInventory.set(slot, ItemStack.EMPTY);
            }
        }

        @Override
        public boolean isExternal() {
            return false;
        }

        @Override
        public boolean isChest() {
            return false;
        }
    }

    private static final class ExternalSource implements Source {
        private final Slot slot;

        ExternalSource(Slot slot) {
            this.slot = slot;
        }

        @Override
        public ItemStack peek() {
            return slot.getStack();
        }

        @Override
        public void consumeOne() {
            slot.decrStackSize(1);
        }

        @Override
        public boolean isExternal() {
            return true;
        }

        @Override
        public boolean isChest() {
            // The pool also holds the grid's own matrix slots (loose leftovers); only the side
            // inventory makes a job depend on its station being open.
            return ExternalSlots.isExternal(slot);
        }
    }

    /**
     * One stored identity of the open network terminal's network ({@link NetworkTerminals}), seen as a
     * single aggregate stack: {@link #peek} is a private copy holding the network's count (clamped to
     * an int), and consumption is paid for up front by {@link #prepareNetwork}, which extracts exactly
     * what phase 1 assigned, so {@link #consumeOne} only hands out an item already taken from the
     * network. Equality is identity (one source per stored identity per craft), which is what the
     * preparation map keys on.
     */
    private static final class NetworkSource implements Source {
        private final NetworkTerminals.View network;
        /** Count-one copy of the identity, for extraction requests. */
        private final ItemStack template;
        private final ItemStack view;
        /** Items extracted by prepareNetwork and not yet consumed. */
        private int prepared;

        NetworkSource(NetworkTerminals.View network, ItemStack stack) {
            this.network = network;
            this.view = stack;
            this.template = stack.copy();
            this.template.setCount(1);
        }

        @Override
        public ItemStack peek() {
            return view;
        }

        @Override
        public void consumeOne() {
            if (prepared > 0) {
                prepared--;
            } else if (network.extract(template, 1, false) < 1) {
                // Unreachable by construction: only craftOnce builds network sources, and it prepares
                // every pick before consuming. Never let a miss pass silently all the same.
                LogManager.getLogger("SprawlCrafting")
                        .warn("Network source consumed without a prepared item: {}", template);
            }
            view.shrink(1);
        }

        /** Returns prepared-but-unconsumed items to the network; whatever it refuses goes to the player. */
        void refund(EntityPlayer player) {
            if (prepared <= 0) {
                return;
            }
            ItemStack back = template.copy();
            back.setCount(prepared);
            prepared = 0;
            ItemStack leftover;
            try {
                leftover = network.insert(back);
            } catch (RuntimeException | LinkageError e) {
                // The network cannot take it back right now: the player gets it, never the void.
                LogManager.getLogger("SprawlCrafting").warn("Could not return {} to the network", back, e);
                leftover = back;
            }
            if (!leftover.isEmpty()) {
                insertIntoMainInventory(player, leftover);
            }
        }

        @Override
        public boolean isExternal() {
            return false;
        }

        @Override
        public boolean isChest() {
            return false;
        }
    }

    /**
     * Returns the player's open crafting grid contents to the main inventory, so the planner and
     * executor (which read only the 36 main slots) can see items that were sitting in the grid.
     * No-op for a non-crafting menu, an empty grid, or when the contents wouldn't all fit.
     */
    public static void clearOpenCraftingGrid(EntityPlayer player) {
        if (player.isSpectator()) {
            return;
        }
        Container menu = player.openContainer;
        if (menu == null) {
            return;
        }
        // The grid input cells are exactly the container's slots backed by an InventoryCrafting —
        // found by type rather than by fixed index, so this works for the vanilla table, the 2x2
        // player grid, and any modded station regardless of where in the container its grid sits.
        List<Slot> gridSlots = new ArrayList<Slot>();
        for (Slot slot : menu.inventorySlots) {
            if (ExternalSlots.isMatrix(slot)) {
                gridSlots.add(slot);
            }
        }
        if (gridSlots.isEmpty()) {
            return;
        }
        List<ItemStack> contents = new ArrayList<ItemStack>();
        for (Slot slot : gridSlots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        if (contents.isEmpty() || !fitsInMainInventory(player.inventory, contents)) {
            return;
        }
        for (Slot slot : gridSlots) {
            slot.putStack(ItemStack.EMPTY);
        }
        for (ItemStack stack : contents) {
            insertIntoMainInventory(player, stack);
        }
        menu.detectAndSendChanges();
    }

    /**
     * Logout safety net: 1.12.2 saves the player ({@code PlayerList.playerLoggedOut}) without
     * closing their container, and {@code ContainerPlayer}'s 2x2 craft matrix is not part of the
     * saved inventory — anything in it at disconnect is silently deleted. Normal GUI closes return
     * it (client sends a close-window packet first), but a client crash skips that, and the
     * final-step hand-off can legitimately leave a staged craft there. Sweeping the matrix into
     * the inventory before the save (the logged-out event fires first) makes the loss impossible;
     * overflow drops at the player's feet, which the world save keeps. Deliberately only the
     * inventory container: a modded station's persistent grid belongs to its tile, and items left
     * in an open vanilla workbench at disconnect are vanilla's own longstanding semantics.
     */
    public static void returnInventoryGridOnLogout(EntityPlayerMP player) {
        for (Slot slot : player.inventoryContainer.inventorySlots) {
            if (ExternalSlots.isMatrix(slot)) {
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty()) {
                    slot.putStack(ItemStack.EMPTY);
                    insertIntoMainInventory(player, stack);
                }
            }
        }
    }

    /** Vanilla recipe-book parity: never consume damaged, enchanted, or renamed stacks. */
    public static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isItemDamaged() && !stack.isItemEnchanted() && !stack.hasDisplayName();
    }

    /**
     * Inserts into the 36 main slots only (merge into matching stacks, then empty slots), dropping
     * any overflow at the player's feet. Deliberately not {@code addItemStackToInventory}, which can
     * stash items into the offhand slot where later steps of the job would not find them.
     * Package-visible: {@link ServerGridPlacer} returns grid contents through the same rules.
     *
     * <p>With a network crafting terminal open, overflow goes into the network before anything is
     * dropped: the player is looking at that network, and an item on the floor beside a terminal is
     * lost far more easily than one in storage. Returns whether the network took any of it.
     */
    static boolean insertIntoMainInventory(EntityPlayer player, ItemStack stack) {
        InventoryPlayer inventory = player.inventory;
        for (int i = 0; i < MAIN && !stack.isEmpty(); i++) {
            ItemStack existing = inventory.mainInventory.get(i);
            if (!existing.isEmpty() && sameItem(existing, stack)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                }
            }
        }
        for (int i = 0; i < MAIN && !stack.isEmpty(); i++) {
            if (inventory.mainInventory.get(i).isEmpty()) {
                int moved = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack placed = stack.copy();
                placed.setCount(moved);
                inventory.mainInventory.set(i, placed);
                stack.shrink(moved);
            }
        }
        inventory.markDirty();
        boolean stored = false;
        if (!stack.isEmpty() && !player.world.isRemote) {
            NetworkTerminals.View network = NetworkTerminals.view(player);
            if (network != null) {
                int before = stack.getCount();
                try {
                    stack = network.insert(stack);
                } catch (RuntimeException | LinkageError e) {
                    // Never lose the stack to a failing AE2 insert: it drops at the feet below.
                    LogManager.getLogger("SprawlCrafting").warn("Network insert failed, dropping {} instead", stack, e);
                }
                stored = stack.getCount() < before;
            }
        }
        if (!stack.isEmpty()) {
            player.dropItem(stack, false);
        }
        return stored;
    }

    /** Non-mutating check that every stack would fit into the 36 main inventory slots together. */
    static boolean fitsInMainInventory(InventoryPlayer inventory, List<ItemStack> stacks) {
        ItemStack[] snapshot = new ItemStack[MAIN];
        for (int i = 0; i < MAIN; i++) {
            snapshot[i] = inventory.mainInventory.get(i).copy();
        }
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            for (int i = 0; i < snapshot.length && !remaining.isEmpty(); i++) {
                ItemStack existing = snapshot[i];
                if (!existing.isEmpty() && sameItem(existing, remaining)) {
                    int room = existing.getMaxStackSize() - existing.getCount();
                    if (room > 0) {
                        int moved = Math.min(room, remaining.getCount());
                        existing.grow(moved);
                        remaining.shrink(moved);
                    }
                }
            }
            for (int i = 0; i < snapshot.length && !remaining.isEmpty(); i++) {
                if (snapshot[i].isEmpty()) {
                    int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                    ItemStack placed = remaining.copy();
                    placed.setCount(moved);
                    snapshot[i] = placed;
                    remaining.shrink(moved);
                }
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Same item, same metadata, same NBT — count-independent (for merge/fit checks). */
    static boolean sameItem(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata()
                && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static String describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getMatchingStacks();
        return options.length > 0 ? options[0].getDisplayName() : "?";
    }
}
