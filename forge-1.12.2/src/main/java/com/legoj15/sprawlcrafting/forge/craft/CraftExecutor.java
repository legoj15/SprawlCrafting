package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.items.SlotItemHandler;
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
 * <p>A bulk sequential step also batches (issue #35): {@link #craftBatch} applies up to k crafts as
 * ONE counted application of the assignment {@link #craftOnce} would make, valid only while every
 * consumed stack, remainder and output provably repeats; anything unprovable runs the single craft
 * instead, so the two paths can never disagree about what a craft does.
 *
 * <p>This is the per-step consumption point of DESIGN.md: ingredients are re-checked against
 * reality here, every half second — the plan only predicted them.
 *
 * <p>Invariants shared with {@link CraftPlanner}:
 * <ul>
 *   <li>Ingredients are consumed from the same pool the planner counted: the 36 main inventory
 *       slots, the open container's crafting grid while no complete craft is staged in it (see
 *       {@link ExternalSlots#materialSlots}) and, when a recognised crafting station is open, its
 *       connected side inventory (a
 *       Tinkers' Construct Crafting Station's bound chest — see {@link ExternalSlots}). Armor and
 *       offhand are never consumed; the ledger's player-own baseline counts them, the cursor stack,
 *       and a staged grid's cells (outside the pool) read-only (see {@link #countRefused}). Results
 *       and crafting remainders are always inserted into the 36
 *       main slots, never the chest: consume-from-chest, deposit-to-player is safe and matches
 *       intuition. With a network crafting terminal open ({@link NetworkTerminals}, the AE2 Crafting
 *       Terminal) the network is a third pool, used after the player's slots; network picks are
 *       extracted all-or-nothing before anything is consumed, and only overflow that would otherwise
 *       drop at the player's feet is inserted into the network.</li>
 *   <li>Damaged, enchanted, or renamed stacks are never consumed, matching what the vanilla
 *       recipe book refuses to auto-fill, except up to what the job's {@link ProducedLedger}
 *       says it produced itself (some mods enchant an item on its first inventory tick, after
 *       the job inserted it), and — only for a job planned with them after the player confirmed
 *       (issue #40) — the protected stacks {@link #isProtected} describes: undamaged but
 *       enchanted and/or renamed, picked only from the 36 main slots and, while a network
 *       crafting terminal is open, the network's stored identities (issue #49), always after
 *       every plain copy of the same item (see {@link #findMatchingSource}'s tier order).</li>
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
            /** How many crafts this result applied (issue #35): 1 per craftOnce, k for a batch. */
            private final int crafts;

            public Success(ItemStack crafted, boolean usedChest, boolean usedNetwork) {
                this(crafted, usedChest, usedNetwork, 1);
            }

            private Success(ItemStack crafted, boolean usedChest, boolean usedNetwork, int crafts) {
                this.crafted = crafted;
                this.usedChest = usedChest;
                this.usedNetwork = usedNetwork;
                this.crafts = crafts;
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

            /** How many crafts this result applied: the count {@link CraftJob#onCraftsPerformed} records. */
            public int crafts() {
                return crafts;
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

    /**
     * One craft of {@code recipeId}. {@code layout} is the plan's per-slot item choice for this craft
     * ({@link CraftJob#currentCraftLayout()}); null or a layout the live pool can no longer honor
     * falls back to {@link SlotAssignment}. {@code allowProtected} is the job's
     * {@link CraftJob#usesProtected()} flag (issue #40): a job planned with the protected tier
     * (the 36 main slots' protected stacks, and an open terminal network's, issue #49) may also
     * pick those — per cell, after the plan's item as a plain copy from any source but before any
     * alternative item (see {@link #findMatchingSource}) — while every other job keeps the plain
     * rule exactly.
     */
    public static CraftResult craftOnce(EntityPlayerMP player, ResourceLocation recipeId,
                                        ProducedLedger<ItemKey, LedgerVariant> ledger, List<ItemKey> layout,
                                        boolean allowProtected) {
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1: match every ingredient slot to a source without mutating, so a shortfall
        // discovered at slot N leaves earlier matches — and the chest — untouched. chosen keeps
        // one entry per ingredient slot (-1 for a gap in the shape) so the simulation below can
        // reconstruct the grid positions.
        Assignment assignment = assign(player, recipe.getIngredients(), ledger, true, layout, allowProtected);
        if (assignment.missing != null) {
            return new CraftResult.MissingIngredient(describe(assignment.missing));
        }

        // What this craft actually produces, asked of the recipe with the exact stacks about to
        // be consumed: getRecipeOutput() alone is NBT-less for input-dependent outputs and, per
        // its contract, empty for dynamic recipes — which the old early-exit misreported as
        // RecipeGone before the ingredients were even checked.
        ItemStack result = computeResult(recipe, assignment.sources, assignment.chosen);
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }
        return applyOnce(player, assignment, result, ledger);
    }

    /**
     * {@link #craftOnce}'s apply over an already matched assignment — the phases after the lookups,
     * shared with {@link #craftBatch}'s fallback (a batch that cannot be proven runs this single
     * craft on the plan it already made; nothing has moved yet, so the assignment is still this
     * craft's exact choice).
     */
    private static CraftResult applyOnce(EntityPlayerMP player, Assignment assignment, ItemStack result,
                                         ProducedLedger<ItemKey, LedgerVariant> ledger) {
        List<Source> sources = assignment.sources;
        int[] chosen = assignment.chosen;
        InventoryPlayer inventory = player.inventory;

        // Phase 1a: every cell's source must actually hand over its share (#52) — a station slot's
        // handler can refuse an extract while its stack reads full. Checked before anything moves,
        // so a short slot reports the item missing instead of crafting without it.
        Map<Integer, Integer> uses = new HashMap<Integer, Integer>();
        for (int idx : chosen) {
            if (idx >= 0) {
                Integer n = uses.get(idx);
                uses.put(idx, n == null ? 1 : n + 1);
            }
        }
        for (Map.Entry<Integer, Integer> use : uses.entrySet()) {
            Source source = sources.get(use.getKey());
            if (source.isExternal() && source.supplyable(use.getValue()) < use.getValue()) {
                return new CraftResult.MissingIngredient(source.peek().getDisplayName());
            }
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
     * Up to {@code maxCrafts} crafts of the recipe as ONE operation, leaving exactly the per-item
     * totals (inventory, chest and network), the job's ledger state, step progress and drops (none)
     * that {@code maxCrafts} successive {@link #craftOnce} calls would (issue #35): one recipe
     * lookup, one assignment scan, one network extraction and one insert per output instead of one
     * of each per craft, which is what a million-craft tree was actually spending its tick on.
     *
     * <p>The batch applies the very assignment {@link #craftOnce} would make right now k times,
     * which is only provable while every pick is the plain pool-order pick of the cell's preferred
     * identity and every consumed stack stays identical (item, metadata, NBT) to the first craft's
     * choice for its cell — the result and the remainders are computed from those exact stacks, so
     * a variant switch mid-batch would change what a craft produces. Supply (player slots, chest,
     * network — the network's share proven by a simulated extraction), the assignment's own
     * repetition rule ({@link SlotAssignment#stableRepeats} over the same pooled stock the choice
     * was made over), output room and the ledger's per-variant state each cap k; anything that
     * cannot be proven — a fallback pick, a credited item the craft also consumes, output without
     * room — runs a single craft instead ({@link #applyOnce}), with exactly craftOnce's result.
     * A batch never drops at the feet: what does not provably fit caps k (the per-craft path's
     * #45 rule is the fallback's).
     */
    public static CraftResult craftBatch(EntityPlayerMP player, ResourceLocation recipeId,
                                         ProducedLedger<ItemKey, LedgerVariant> ledger, List<ItemKey> layout,
                                         int maxCrafts, boolean allowProtected) {
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            return new CraftResult.RecipeGone();
        }
        Assignment assignment = assign(player, recipe.getIngredients(), ledger, true, layout, allowProtected);
        if (assignment.missing != null) {
            return new CraftResult.MissingIngredient(describe(assignment.missing));
        }
        ItemStack result = computeResult(recipe, assignment.sources, assignment.chosen);
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }
        Batch batch = batchPlan(player, assignment, result, recipe.getIngredients(), layout, maxCrafts,
                allowProtected);
        if (batch == null) {
            return applyOnce(player, assignment, result, ledger);
        }
        return applyBatch(player, assignment, batch, result, ledger);
    }

    /** A worked-out batch: what one craft consumes and returns, and how many crafts it stands for. */
    private static final class Batch {
        /** How many crafts this batch applies (at least 2). */
        final int crafts;
        /** Per preferred {@link ItemKey}: the exact stack every craft consumes (the first craft's pick). */
        final Map<ItemKey, ItemStack> identity;
        /** Per preferred {@link ItemKey}: how many cells of one craft take it. */
        final Map<ItemKey, Long> use;
        /** Per-craft remainder totals, grouped by exact remainder stack (identical groups merged). */
        final List<ItemStack> remainders;
        final List<Long> remainderCounts;
        /** The network's share of the whole batch, per network source (extracted all-or-nothing). */
        final Map<NetworkSource, Integer> networkTakes;

        Batch(int crafts, Map<ItemKey, ItemStack> identity, Map<ItemKey, Long> use,
              List<ItemStack> remainders, List<Long> remainderCounts, Map<NetworkSource, Integer> networkTakes) {
            this.crafts = crafts;
            this.identity = identity;
            this.use = use;
            this.remainders = remainders;
            this.remainderCounts = remainderCounts;
            this.networkTakes = networkTakes;
        }
    }

    /**
     * Works out the batch of up to {@code maxCrafts} crafts, or null when this call may not batch
     * (k = 1: the caller's {@link #applyOnce}). Every cap below is chosen so the batch's final state
     * is exactly k single crafts': the pick rule (plain tier-1 of the preferred identity, the
     * assignment's {@code preferredFilled}), the identity of every consumed stack (the pool-order drain of
     * copies identical to the first craft's pick), {@link SlotAssignment#stableRepeats} over the
     * assignment's own stock view, live network supply, int safety and output room.
     */
    private static Batch batchPlan(EntityPlayerMP player, Assignment assignment, ItemStack result,
                                   List<Ingredient> ingredients, List<ItemKey> layout,
                                   int maxCrafts, boolean allowProtected) {
        // A fallback pick (a ledger-admitted, protected or any-plain match) depends on more than the
        // shared pool order; k single crafts would not repeat it provably.
        if (maxCrafts <= 1 || !assignment.preferredFilled) {
            return null;
        }
        // A carried layout must be the one this assignment honoured: the per-craft path re-checks the
        // layout against the live stock before every craft, so a stock that has drifted back over the
        // layout's use would switch a later craft to the layout's items while the batch repeats this
        // assignment — a re-check the repetition proof below does not model. A null layout (a step
        // without one) chose with SlotAssignment alone and keeps the plain proof.
        if (layout != null && !assignment.layoutHonored) {
            return null;
        }
        List<Source> sources = assignment.sources;
        Map<ItemKey, Long> use = new LinkedHashMap<ItemKey, Long>();
        Map<ItemKey, ItemStack> identity = new LinkedHashMap<ItemKey, ItemStack>();
        List<List<ItemKey>> slots = new ArrayList<List<ItemKey>>();
        Set<ItemKey> candidates = new HashSet<ItemKey>();
        for (int n = 0; n < ingredients.size(); n++) {
            Ingredient ingredient = ingredients.get(n);
            if (ingredient == null || ingredient == Ingredient.EMPTY) {
                continue;
            }
            ItemKey preferred = assignment.preferred[n];
            ItemStack picked = sources.get(assignment.chosen[n]).peek();
            // Every cell sharing a preferred identity must share its exact picked stack too: the
            // batch repeats ONE stack per cell (result and remainders are computed from it), and two
            // NBT variants under one ItemKey would make a later craft's output differ from the first's.
            ItemStack same = identity.get(preferred);
            if (same == null) {
                // A copy, never the live slot stack: the drain empties that slot, and an emptied 1.12.2
                // stack reads as air, so a live reference would stop the drain at the next slot and
                // leave the rest of the batch's input unconsumed (a duplication, seen in-game on #39).
                identity.put(preferred, picked.copy());
            } else if (!sameItem(same, picked)) {
                return null;
            }
            use.merge(preferred, 1L, Long::sum);
            List<ItemKey> alternatives = CraftPlanner.slotAlternatives(ingredient);
            slots.add(alternatives);
            candidates.addAll(alternatives);
        }
        // The remainders every craft returns, from the exact consumed stacks (identical across the
        // batch, so one lookup stands for all k). Looked up before anything is drawn, so a throwing
        // container-item mod cannot strand extracted items.
        List<ItemStack> remainders = new ArrayList<ItemStack>();
        List<Long> remainderCounts = new ArrayList<Long>();
        for (Map.Entry<ItemKey, ItemStack> pick : identity.entrySet()) {
            ItemStack one = pick.getValue().copy();
            one.setCount(1);
            Item item = one.getItem();
            if (!item.hasContainerItem(one)) {
                continue;
            }
            ItemStack remainder = item.getContainerItem(one);
            if (remainder.isEmpty()) {
                continue;
            }
            if (remainder.getCount() != 1) {
                // The counts below are per consumed stack; a multi-count remainder would be scaled
                // wrong, so the single craft (which inserts the remainder as returned) handles it.
                return null;
            }
            long perCraft = use.get(pick.getKey());
            boolean merged = false;
            for (int g = 0; g < remainders.size(); g++) {
                if (sameItem(remainders.get(g), remainder)) {
                    remainderCounts.set(g, remainderCounts.get(g) + perCraft);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                remainders.add(remainder);
                remainderCounts.add(perCraft);
            }
        }
        // A craft credited an item it also consumes (its own result in a slot, or a remainder equal to
        // an input) cannot batch: each single craft applies and inserts its output BEFORE the next
        // craft assigns, so craft i draws stock craft i-1 just put back — an interleave one counted
        // application cannot reproduce exactly.
        Map<ItemKey, Long> made = new LinkedHashMap<ItemKey, Long>();
        for (int g = 0; g < remainders.size(); g++) {
            made.merge(ItemKey.of(remainders.get(g)), remainderCounts.get(g), Long::sum);
        }
        int perResult = Math.max(1, result.getCount());
        made.merge(ItemKey.of(result), (long) perResult, Long::sum);
        for (ItemKey credited : made.keySet()) {
            if (use.containsKey(credited)) {
                return null;
            }
        }
        // The same rule under the ledger's own identity, which drops meta for non-subtyped and
        // damageable items: an output that shares a consumed input's ledger key charges against it.
        Set<ItemKey> consumedLedger = new HashSet<ItemKey>();
        for (ItemStack consumed : identity.values()) {
            consumedLedger.add(ledgerKey(consumed));
        }
        if (consumedLedger.contains(ledgerKey(result))) {
            return null;
        }
        for (ItemStack remainder : remainders) {
            if (consumedLedger.contains(ledgerKey(remainder))) {
                return null;
            }
        }
        for (ItemStack remainder : remainders) {
            if (ItemKey.of(remainder).equals(ItemKey.of(result))) {
                return null; // the result's and the remainder's room proofs would count the same stacks
            }
        }
        // No refused stack of a candidate identity — or of any item a cell's ingredient would still
        // test-accept — may sit in the pool. With one there the ledger holds counts against items the
        // batch consumes, and both ledger-only ways an assignment stops provably repeating open up:
        // an admitted altered pick draws per-variant headroom one craft at a time, which item totals
        // cannot reproduce, and the #26 preference stock counts exactly those stacks
        // ({@link #countAdmitted}), so a choice fed by headroom cannot be repeated from item counts.
        // With none of them every pick is plain, and one counted charge of k*m equals k charges of m
        // (the refused baseline the charge reads cannot move while only plain stacks are consumed).
        // Protected stacks of a protected-planned job are plain material in exactly that sense, but a
        // batch drains only copies identical to the first craft's plain pick, so they never enter.
        for (Source source : sources) {
            ItemStack stack = source.peek();
            if (!stack.isEmpty() && !usableAsIngredient(stack, allowProtected)
                    && (candidates.contains(ItemKey.of(stack)) || acceptedByAny(ingredients, stack))) {
                return null;
            }
        }
        final Map<ItemKey, Integer> stock = assignment.stock;
        long k = SlotAssignment.stableRepeats(slots, key -> {
            Integer have = stock.get(key);
            return have == null ? 0L : have.longValue();
        }, use, made, maxCrafts);
        for (long perCraft : use.values()) {
            k = Math.min(k, Integer.MAX_VALUE / perCraft); // ledger charges and drains are int-sized
        }
        k = Math.min(k, Integer.MAX_VALUE / perResult);
        for (long perCraft : remainderCounts) {
            k = Math.min(k, Integer.MAX_VALUE / perCraft); // summed remainders insert as one int stack
        }
        // k crafts drain, per identity, the pool's plain copies identical to the first craft's pick,
        // in pool order — every single craft's tier-1 pick walks the same sources from the front. The
        // batch is that drain only while every copy it takes is identical: a different NBT variant
        // under the same ItemKey would change the result computed from the consumed stacks, and a
        // protected or refused copy would change the pick tier. Non-matching and non-plain sources
        // never stop the drain: the per-craft pick skips them while identical copies remain beyond.
        InventoryPlayer inventory = player.inventory;
        Map<ItemKey, Long> ownCopies = new LinkedHashMap<ItemKey, Long>();
        Map<ItemKey, NetworkSource> suppliers = new LinkedHashMap<ItemKey, NetworkSource>();
        for (Map.Entry<ItemKey, ItemStack> pick : identity.entrySet()) {
            ItemKey key = pick.getKey();
            ItemStack stack = pick.getValue();
            long perCraft = use.get(key);
            long own = 0; // identical copies ahead of the network: the player's slots, then the chest
            NetworkSource supplier = null; // one network source per stored identity
            long networkCount = 0;
            for (Source source : sources) {
                ItemStack held = source.peek();
                if (held.isEmpty() || !key.equals(ItemKey.of(held)) || !usableAsIngredient(held)) {
                    continue;
                }
                if (!sameItem(held, stack)) {
                    break; // the drain would reach a different variant: k must stop before it
                }
                if (source instanceof NetworkSource) {
                    supplier = (NetworkSource) source;
                    networkCount = held.getCount();
                } else {
                    own += held.getCount();
                }
            }
            ownCopies.put(key, own);
            if (supplier != null) {
                suppliers.put(key, supplier);
            }
            k = Math.min(k, (own + networkCount) / perCraft);
            if (supplier != null) {
                // The listing's count is a cache; only a simulated extraction proves the network can
                // hand the share over (power, a concurrent export bus). A short answer caps k to what
                // is provable; a later, smaller k only shrinks earlier shares, which stay covered.
                long share = k * perCraft - Math.min(k * perCraft, own);
                if (share > 0) {
                    long got = supplier.network.extract(supplier.template,
                            (int) Math.min(share, Integer.MAX_VALUE), true);
                    if (got < share) {
                        k = Math.min(k, (own + got) / perCraft);
                    }
                }
            }
        }
        // Output room, read before anything moves (conservative: slots the batch's own consumption
        // frees are not counted). The result may have the empty slots and the network — the network
        // only for the whole overflow a simulated insert proves it takes; anything less caps k so the
        // result fits the main inventory alone. Each remainder gets its own partial stacks' merge
        // room only, so two outputs never count the same free space.
        long room = inventoryRoom(inventory, result, true);
        NetworkTerminals.View network = NetworkTerminals.view(player);
        if (k * perResult > room) {
            boolean proven = false;
            if (network != null) {
                ItemStack overflow = result.copy();
                overflow.setCount((int) Math.min(k * perResult - room, Integer.MAX_VALUE));
                try {
                    proven = network.accepts(overflow);
                } catch (RuntimeException | LinkageError e) {
                    LogManager.getLogger("SprawlCrafting")
                            .warn("SprawlCrafting: network room probe failed, not batching into it", e);
                }
            }
            if (!proven) {
                k = Math.min(k, room / perResult);
            }
        }
        if (!remainders.isEmpty()) {
            // Remainders could drop at the feet with no network open to catch them; one craft keeps
            // that the per-craft path's own rule.
            if (network == null) {
                return null;
            }
            for (int g = 0; g < remainders.size(); g++) {
                k = Math.min(k, inventoryRoom(inventory, remainders.get(g), false) / remainderCounts.get(g));
            }
        }
        if (k <= 1) {
            return null;
        }
        // The network's share of the final k, per identity: what the pool's own copies cannot cover.
        Map<NetworkSource, Integer> networkTakes = new LinkedHashMap<NetworkSource, Integer>();
        for (Map.Entry<ItemKey, ItemStack> pick : identity.entrySet()) {
            NetworkSource supplier = suppliers.get(pick.getKey());
            if (supplier == null) {
                continue;
            }
            long perCraft = use.get(pick.getKey());
            long share = k * perCraft - Math.min(k * perCraft, ownCopies.get(pick.getKey()));
            if (share > 0) {
                networkTakes.put(supplier, (int) Math.min(share, Integer.MAX_VALUE));
            }
        }
        return new Batch((int) k, identity, use, remainders, remainderCounts, networkTakes);
    }

    /**
     * Applies a worked-out batch: the single craft's phases ({@link #applyOnce}), scaled to k crafts.
     * The network's share is extracted all-or-nothing first, exactly as one craft's
     * {@link #prepareNetwork}; a short draw has been refunded and the whole call falls back to the
     * single craft, whose own preparation then reports the item missing (or succeeds — the network
     * may still cover one craft).
     */
    private static CraftResult applyBatch(EntityPlayerMP player, Assignment assignment, Batch batch,
                                          ItemStack result, ProducedLedger<ItemKey, LedgerVariant> ledger) {
        // Read-only proof that the drain below covers the whole batch before anything moves: a drain
        // that ran short would hand out k results for fewer inputs.
        for (Map.Entry<ItemKey, ItemStack> pick : batch.identity.entrySet()) {
            long need = (long) batch.crafts * batch.use.get(pick.getKey());
            for (Source source : assignment.sources) {
                ItemStack held = source.peek();
                if (held.isEmpty() || !pick.getKey().equals(ItemKey.of(held)) || !usableAsIngredient(held)) {
                    continue;
                }
                if (!sameItem(held, pick.getValue())) {
                    break;
                }
                int take = (int) Math.min(need, held.getCount());
                if (source.supplyable(take) < take) {
                    break; // a station slot that would hand over less (#52): applyOnce reports it
                }
                need -= take;
            }
            if (need > 0) {
                return applyOnce(player, assignment, result, ledger);
            }
        }
        ItemStack networkShort = extractNetwork(player, batch.networkTakes);
        if (!networkShort.isEmpty()) {
            return applyOnce(player, assignment, result, ledger);
        }
        InventoryPlayer inventory = player.inventory;
        List<ItemStack> remainders = new ArrayList<ItemStack>();
        for (int g = 0; g < batch.remainders.size(); g++) {
            ItemStack stack = batch.remainders.get(g).copy();
            stack.setCount((int) Math.min((long) batch.crafts * batch.remainderCounts.get(g),
                    Integer.MAX_VALUE));
            remainders.add(stack);
        }
        ItemStack resultOut = result.copy();
        resultOut.setCount((int) Math.min((long) batch.crafts * Math.max(1, result.getCount()),
                Integer.MAX_VALUE));
        boolean drained = false; // the remainders are owed only once the whole drain has run
        try {
            // Charged before anything is consumed: the counted picks read each cell's exact stack
            // (its ledger key), and a source the drain empties below would have nothing left to read.
            // The charge itself touches only the tally and ledger, never the pool.
            assignment.chargeCraftsTo(ledger, batch.crafts);
            boolean touchedExternal = false;
            boolean usedChest = false;
            boolean usedNetwork = false;
            for (Map.Entry<ItemKey, ItemStack> pick : batch.identity.entrySet()) {
                ItemKey key = pick.getKey();
                ItemStack stack = pick.getValue();
                long need = (long) batch.crafts * batch.use.get(key);
                for (Source source : assignment.sources) {
                    if (need <= 0) {
                        break;
                    }
                    ItemStack held = source.peek();
                    if (held.isEmpty() || !key.equals(ItemKey.of(held)) || !usableAsIngredient(held)) {
                        continue; // never a tier-1 pick; the drain skips it
                    }
                    if (!sameItem(held, stack)) {
                        break; // a different variant: k was capped to stop before it (defensive here)
                    }
                    int take = (int) Math.min(need, held.getCount());
                    source.consume(take);
                    need -= take;
                    touchedExternal |= source.isExternal();
                    usedChest |= source.isChest();
                    usedNetwork |= source instanceof NetworkSource;
                }
                if (need > 0) {
                    // Unreachable while the pre-pass mirrors this loop. If it drifts, fail loud rather
                    // than hand out k results for short inputs: the copies already drained and the
                    // ledger charge are not restored (only the prepared network share is refunded).
                    throw new IllegalStateException("SprawlCrafting batch drain short by " + need + " of " + key);
                }
            }
            inventory.markDirty();
            drained = true;
            ItemStack crafted = result.copy();
            // The result before the remainders: the plan gave only the result the empty slots and the
            // network's proven share, so a remainder inserted first could take space the result was
            // promised. Totals are the same either way (each output fits its own promised room).
            usedNetwork |= insertIntoMainInventory(player, resultOut);
            // Removed before inserting, so the failure path never hands one out twice.
            while (!remainders.isEmpty()) {
                usedNetwork |= insertIntoMainInventory(player, remainders.remove(0));
            }
            ledger.produced(ledgerKey(crafted), (long) batch.crafts * crafted.getCount());
            if (touchedExternal) {
                player.openContainer.detectAndSendChanges();
            }
            return new CraftResult.Success(crafted, usedChest, usedNetwork, batch.crafts);
        } catch (Throwable failure) {
            returnPreparedNetworkItems(player, assignment.sources, failure);
            for (ItemStack remainder : drained ? remainders : Collections.<ItemStack>emptyList()) {
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
        // a direct craft has no plan layout
        ItemKey[] preferred = preferredKeys(sources, ingredients, null, null, false).preferred;
        int[] reserved = new int[sources.size()];
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient == Ingredient.EMPTY) {
                continue;
            }
            int idx = findMatchingSource(sources, ingredient, preferred[i], reserved, null, false, false);
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
     * network can hold thousands, and this runs once per craft of a burst). The listing carries
     * every stored identity, protected ones included (issue #49); the plain passes refuse those
     * through {@code usableAsIngredient}, so only a job whose {@code allowProtected} tier reaches
     * {@link #findProtectedSource} can ever pick one.
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
        return extractNetwork(player, needed);
    }

    /**
     * Extracts each network source's {@code needed} count, all or nothing: every extraction is
     * simulated first (no side effects on a shortfall), then done for real; a real extraction that
     * still comes up short (power, a concurrent export bus between the two calls) puts everything
     * already extracted back — or, should the network refuse it, into the player's inventory, never
     * the void. Returns EMPTY on success, else a stack naming the short item. Both {@link #applyOnce}
     * (one craft's picks) and {@link #applyBatch} (the whole batch's share) run through here.
     */
    private static ItemStack extractNetwork(EntityPlayer player, Map<NetworkSource, Integer> needed) {
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
     * when no acceptable stack matches and {@code ledger} (null: never) still admits its exact
     * variant through {@link LedgerTally#admits}: only rejected copies above those the player held
     * before the job produced anything, per variant. Every pick is recorded in the tally, which
     * {@link Assignment#chargeTo} charges: rejected picks, plus plain picks beyond the job's
     * rejected copies of that identity the player still
     * holds. Package-visible: the final-step hand-off ({@link ServerGridPlacer}) lays out the same
     * choice.
     *
     * <p>{@code layout} is the plan's per-slot item choice for this craft (one entry per non-empty
     * cell in cell order; null or a mismatch: decide with {@link SlotAssignment}), see
     * {@link #preferredKeys}.
     */
    static Assignment assign(EntityPlayer player, List<Ingredient> ingredients,
                             ProducedLedger<ItemKey, LedgerVariant> ledger, List<ItemKey> layout) {
        return assign(player, ingredients, ledger, false, layout, false);
    }

    /**
     * {@link #assign(EntityPlayer, List, ProducedLedger, List)} with the protected tier
     * (issue #40): {@code allowProtected} is the job's {@link CraftJob#usesProtected()} flag, whose
     * assignment may also pick protected stacks from the 36 main slots — in
     * {@link #findMatchingSource}'s tier order, after every plain copy of the item.
     */
    static Assignment assign(EntityPlayer player, List<Ingredient> ingredients,
                             ProducedLedger<ItemKey, LedgerVariant> ledger, List<ItemKey> layout,
                             boolean allowProtected) {
        return assign(player, ingredients, ledger, false, layout, allowProtected);
    }

    /**
     * {@link #assign(EntityPlayer, List, ProducedLedger, List, boolean)} over a pool that, with
     * {@code withNetwork}, also holds the open network terminal's stock. Only {@link #craftOnce}
     * passes true: it is the one consumer that {@link #prepareNetwork extracts} its network picks
     * before taking them. The grid placer's {@link Assignment#take} never sees a network source.
     * The pool is built once and pooled from the start (unlike the 1.16.5 executor's separate
     * inventory-only pass in its {@code prepare}), so a protected job with a station chest or
     * terminal open is already the "pooled assignment" the #40 rule wants: its per-source order
     * keeps every plain copy — the player's, the chest's, the network's — ahead of any protected one.
     */
    private static Assignment assign(EntityPlayer player, List<Ingredient> ingredients,
                                     ProducedLedger<ItemKey, LedgerVariant> ledger, boolean withNetwork,
                                     List<ItemKey> layout, boolean allowProtected) {
        List<Source> sources = buildSources(player, player.inventory,
                withNetwork ? ingredientItems(ingredients) : null);
        // First with the job's admitted altered copies counted in the preference rule and the plan's
        // layout check (#26); when that leaves an ingredient unfilled, the pre-#26 rule exactly. The
        // rule cannot see which cells accept an altered copy, so counting one can steer a cell to an
        // identity only another cell could take; the second pass makes the change never worse than
        // before it. A pass changes no pool state (sources are only peeked; the ledger's preexisting
        // snapshot is rewritten with equal values).
        Assignment first = assignPass(player, sources, ingredients, ledger, layout, true, allowProtected);
        if (first.complete() || ledger == null) {
            return first;
        }
        return assignPass(player, sources, ingredients, ledger, layout, false, allowProtected);
    }

    /** One {@link #assign} attempt over {@code sources}; {@code admittedPreference}: the #26 rule. */
    private static Assignment assignPass(EntityPlayer player, List<Source> sources, List<Ingredient> ingredients,
                                         ProducedLedger<ItemKey, LedgerVariant> ledger, List<ItemKey> layout,
                                         boolean admittedPreference, boolean allowProtected) {
        int[] chosen = new int[ingredients.size()];
        int[] reserved = new int[sources.size()];
        LedgerTally<ItemKey, LedgerVariant> tally = new LedgerTally<ItemKey, LedgerVariant>();
        if (ledger != null) {
            countRefused(player, sources, tally, allowProtected);
            tally.open(ledger);
        }
        CellChoice choice = preferredKeys(sources, ingredients, layout,
                ledger != null && admittedPreference ? tally : null, allowProtected);
        ItemKey[] preferred = choice.preferred;
        // Whether every non-empty cell took its preferred identity straight from the pool's plain
        // stock, in pool order (no fallback tier): only such a pick is a function of the item counts
        // alone, which is what a batch's k-fold repetition proof needs (issue #35).
        boolean preferredFilled = true;
        for (int i = 0; i < chosen.length; i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient == Ingredient.EMPTY) {
                chosen[i] = -1;
                continue;
            }
            int idx = findMatchingSource(sources, ingredient, preferred[i], reserved, ledger == null ? null : tally,
                    admittedPreference, allowProtected);
            if (idx < 0) {
                return new Assignment(sources, chosen, tally, ingredient, choice, preferredFilled);
            }
            // Read before the reservation below raises it: the plain tier-1 pick of the preferred
            // identity this cell could have made, the one pick a batch can repeat.
            preferredFilled &= preferred[i] != null
                    && idx == findPreferredPlainSource(sources, ingredient, preferred[i], reserved);
            reserved[idx]++;
            chosen[i] = idx;
            if (ledger != null) {
                ItemStack stack = sources.get(idx).peek();
                // A protected pick counts as plain in the ledger (#40): the plan counted the stack as
                // material, and countRefused keeps protected stacks out of the refused baseline, so
                // recording it refused would wrongly spend the job's altered-copy headroom.
                boolean refused = !usableAsIngredient(stack, allowProtected);
                tally.picked(ledgerKey(stack), refused ? variantOf(stack) : null, refused);
            }
        }
        return new Assignment(sources, chosen, tally, null, choice, preferredFilled);
    }

    /**
     * Records in {@code tally}, by {@link #ledgerKey} and variant, every stack the player holds that
     * {@link #usableAsIngredient} rejects: the pool ({@link #buildSources}) plus — counted for the
     * player's-own baseline only, never pickable — the offhand and armour slots, the cursor stack, and
     * the open container's matrix slots that are not already pool sources (a staged grid's are not),
     * so moving a stack between holdings cannot make it look like one of the job's copies. A
     * protected-planned job ({@code allowProtected}, #40) does not count its protected stacks here:
     * to it they are plain material (picked without admission, charged like plain picks), and a
     * baseline entry would let the ledger refuse or double-charge them; damaged stacks stay refused
     * for every job.
     */
    private static void countRefused(EntityPlayer player, List<Source> sources,
                                     LedgerTally<ItemKey, LedgerVariant> tally, boolean allowProtected) {
        // Summed in a long and saturated per variant before the tally sees it: a network source's peek
        // is the network's whole count (up to Integer.MAX_VALUE), and an int overflow would flip held
        // negative and admit the player's own altered copies. Over-counting held is the safe side. The
        // identity rides along: a variant (item, metadata, NBT) determines its key.
        Map<LedgerVariant, Long> refused = new LinkedHashMap<LedgerVariant, Long>();
        Map<LedgerVariant, ItemKey> keys = new HashMap<LedgerVariant, ItemKey>();
        for (Source source : sources) {
            noteRefused(source.peek(), refused, keys, allowProtected);
        }
        InventoryPlayer inventory = player.inventory;
        for (ItemStack stack : inventory.offHandInventory) {
            noteRefused(stack, refused, keys, allowProtected);
        }
        for (ItemStack stack : inventory.armorInventory) {
            noteRefused(stack, refused, keys, allowProtected);
        }
        noteRefused(inventory.getItemStack(), refused, keys, allowProtected);
        // Compared by Slot identity against the pool's own slots (the external sources wrap exactly
        // these), so an unstaged matrix — already a pool source — is never counted twice.
        Container container = player.openContainer;
        if (container != null) {
            Set<Slot> pooled = new HashSet<Slot>(ExternalSlots.materialSlots(player));
            for (Slot slot : container.inventorySlots) {
                if (ExternalSlots.isMatrix(slot) && !pooled.contains(slot)) {
                    noteRefused(slot.getStack(), refused, keys, allowProtected);
                }
            }
        }
        for (Map.Entry<LedgerVariant, Long> entry : refused.entrySet()) {
            tally.held(keys.get(entry.getKey()), entry.getKey(),
                    (int) Math.min(entry.getValue(), Integer.MAX_VALUE));
        }
    }

    /** Adds {@code stack} to the per-variant refused totals when the filter rejects it. */
    private static void noteRefused(ItemStack stack, Map<LedgerVariant, Long> refused,
                                    Map<LedgerVariant, ItemKey> keys, boolean allowProtected) {
        if (!stack.isEmpty() && !usableAsIngredient(stack, allowProtected)) {
            LedgerVariant variant = variantOf(stack);
            Long sum = refused.get(variant);
            refused.put(variant, (sum == null ? 0L : sum) + stack.getCount());
            keys.put(variant, ledgerKey(stack));
        }
    }

    static final class Assignment {
        private final List<Source> sources;
        private final int[] chosen;
        private final LedgerTally<ItemKey, LedgerVariant> tally;
        private final Ingredient missing;
        /** {@link #preferredKeys}'s answer for the pass that made this assignment (issue #35). */
        private final ItemKey[] preferred;
        /** The pooled stock {@link #preferred} was chosen over — {@link SlotAssignment#stableRepeats}'s view. */
        private final Map<ItemKey, Integer> stock;
        /** Whether a carried plan layout is the choice this assignment honoured. */
        private final boolean layoutHonored;
        /** Whether every non-empty cell took its preferred identity as a plain pool-order pick. */
        private final boolean preferredFilled;

        private Assignment(List<Source> sources, int[] chosen, LedgerTally<ItemKey, LedgerVariant> tally,
                           Ingredient missing, CellChoice choice, boolean preferredFilled) {
            this.sources = sources;
            this.chosen = chosen;
            this.tally = tally;
            this.missing = missing;
            this.preferred = choice.preferred;
            this.stock = choice.stock;
            this.layoutHonored = choice.layoutHonored;
            this.preferredFilled = preferredFilled;
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

        void chargeTo(ProducedLedger<ItemKey, LedgerVariant> ledger) {
            tally.chargeTo(ledger);
        }

        /**
         * {@code crafts} crafts' ledger charge in one go: the single-craft picks this assignment
         * already recorded, plus {@code crafts - 1} more per cell, then one charge. Equal to that
         * many per-craft charges because a batch runs only on plain tier-1 picks over a refused
         * baseline only plain consumption cannot move (see {@link #chargeTo}'s keep rule). Run
         * before any consumption: the counted picks read each cell's live stack for its ledger key.
         */
        void chargeCraftsTo(ProducedLedger<ItemKey, LedgerVariant> ledger, int crafts) {
            if (crafts > 1) {
                for (int i = 0; i < chosen.length; i++) {
                    if (chosen[i] >= 0) {
                        tally.picked(ledgerKey(sources.get(chosen[i]).peek()), null, false, crafts - 1);
                    }
                }
            }
            chargeTo(ledger);
        }
    }

    /**
     * {@link #preferredKeys}'s answer: the per-cell identities, the pooled stock they were chosen
     * over, and whether a carried plan layout was honoured (null layout, or one that no longer fits,
     * falls back to {@link SlotAssignment} — the caller decides what that means).
     */
    private static final class CellChoice {
        private final ItemKey[] preferred;
        private final Map<ItemKey, Integer> stock;
        private final boolean layoutHonored;

        CellChoice(ItemKey[] preferred, Map<ItemKey, Integer> stock, boolean layoutHonored) {
            this.preferred = preferred;
            this.stock = stock;
            this.layoutHonored = layoutHonored;
        }
    }

    /**
     * The ledger's variant of a stack — item, metadata (damage included), and NBT — as a count-1
     * copy: what the per-variant baseline ({@link ProducedLedger#preexisting}) and per-variant
     * admission ({@link LedgerTally#admits}) are keyed by. {@link #hashCode} is the item's identity
     * hash alone (collisions only within one item, which {@link #equals} separates); {@link #equals}
     * is the tree's exact stack equality.
     */
    static final class LedgerVariant {
        private final ItemStack stack;

        LedgerVariant(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LedgerVariant && sameItem(stack, ((LedgerVariant) other).stack);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(stack.getItem());
        }
    }

    /** The ledger's variant of one copy of {@code stack}; its count is not part of the variant. */
    static LedgerVariant variantOf(ItemStack stack) {
        ItemStack one = stack.copy();
        one.setCount(1);
        return new LedgerVariant(one);
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
     * Per ingredient, the identity the plan's layout fixes for it when that choice still fits the
     * live pool, else the identity {@link SlotAssignment} picks from it (null for a gap or when
     * nothing usable fits). The layout is honored only when every one of its identities is still an
     * alternative of its slot and the pooled stock — the usable stacks of the whole pool, exactly
     * what the plan counted — covers this craft's use of each; the player may have moved items
     * since, and a stale layout must not override what the pool can actually supply.
     * {@link SlotAssignment} runs over the same slot lists ({@link CraftPlanner#slotAlternatives})
     * and the same pool counts, so its fallback still takes what a plan made without layouts
     * counted on.
     * With {@code admitting} (the #26 pass of {@link #assign}) the job's admitted altered copies
     * join that stock too ({@link #countAdmitted}), since the plan counted them as well, and with
     * {@code allowProtected} so do the protected stacks of a job planned with them (issue #40):
     * the plan's counts included them, and a layout chosen or honoured over less would not fit.
     * Protected stacks count in a player main-slot source and — with a terminal open — a network
     * source (issue #49), exactly the two pool positions {@link #findProtectedSource} scans; the
     * station chest and the grid stay plain pools either way.
     */
    private static CellChoice preferredKeys(List<Source> sources, List<Ingredient> ingredients,
                                            List<ItemKey> layout, LedgerTally<ItemKey, LedgerVariant> admitting,
                                            boolean allowProtected) {
        Map<ItemKey, Integer> stock = new HashMap<ItemKey, Integer>();
        for (Source source : sources) {
            ItemStack stack = source.peek();
            boolean protectedMaterial = allowProtected
                    && (source instanceof PlayerSource || source instanceof NetworkSource);
            if (!stack.isEmpty() && usableAsIngredient(stack, protectedMaterial)) {
                ItemKey key = ItemKey.of(stack);
                Integer have = stock.get(key);
                stock.put(key, NetworkTerminals.saturatedAdd(have == null ? 0 : have, stack.getCount()));
            }
        }
        if (admitting != null) {
            countAdmitted(ingredients, sources, admitting, stock, allowProtected);
        }
        List<List<ItemKey>> cells = new ArrayList<List<ItemKey>>();
        for (Ingredient ingredient : ingredients) {
            cells.add(ingredient == null || ingredient == Ingredient.EMPTY
                    ? null : CraftPlanner.slotAlternatives(ingredient));
        }
        // The layout is indexed by non-empty cell, as the solver's slots are.
        List<List<ItemKey>> slots = new ArrayList<List<ItemKey>>();
        for (List<ItemKey> cell : cells) {
            if (cell != null) {
                slots.add(cell);
            }
        }
        if (layout != null && layout.size() == slots.size() && layoutFits(layout, slots, stock)) {
            ItemKey[] preferred = new ItemKey[cells.size()];
            int s = 0;
            for (int n = 0; n < cells.size(); n++) {
                if (cells.get(n) != null) {
                    preferred[n] = layout.get(s++);
                }
            }
            return new CellChoice(preferred, stock, true);
        }
        return new CellChoice(SlotAssignment.assignCells(cells, stock).toArray(new ItemKey[0]), stock, false);
    }

    /**
     * Whether the plan's layout can still be laid on the live pool: every identity remains an
     * alternative of its slot (the i-th entry belongs to the i-th non-empty cell, the order
     * {@code slots} is built in), and the pool holds at least as many of each identity as this one
     * craft uses of it.
     */
    private static boolean layoutFits(List<ItemKey> layout, List<List<ItemKey>> slots,
                                      Map<ItemKey, Integer> stock) {
        Map<ItemKey, Integer> uses = new HashMap<ItemKey, Integer>();
        for (int i = 0; i < layout.size(); i++) {
            ItemKey item = layout.get(i);
            if (item == null || !slots.get(i).contains(item)) { // null never satisfies a slot
                return false;
            }
            Integer have = uses.get(item);
            uses.put(item, (have == null ? 0 : have) + 1);
        }
        for (Map.Entry<ItemKey, Integer> use : uses.entrySet()) {
            Integer inStock = stock.get(use.getKey());
            if ((inStock == null ? 0 : inStock) < use.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds to {@code stock} the refused stacks of the pool that {@code tally} would admit: the job's
     * altered intermediates, which the plan's counts include (the solver credits every item a step
     * produces, altered or not), so {@link #preferredKeys} and the plan's layout check must see them
     * too (#26). Each stack adds at most what {@link LedgerTally#variantHeadroom} and
     * {@link LedgerTally#identityHeadroom} still allow, drawn down as stacks are counted, under its
     * {@link #ledgerKey}: the plan credited the fresh output, whose key is its ledger identity, and an
     * altered damageable copy's own {@link ItemKey#of} carries its damage, which no slot alternative
     * names. A copy no cell of this recipe accepts is skipped: it would only inflate the count the
     * rule spreads over the cells. A protected-planned job's protected stacks never come here:
     * they are already plain stock ({@link #preferredKeys}), not ledger-admitted ones.
     */
    private static void countAdmitted(List<Ingredient> ingredients, List<Source> sources, LedgerTally<ItemKey, LedgerVariant> tally,
                                      Map<ItemKey, Integer> stock, boolean allowProtected) {
        Map<LedgerVariant, Long> variantLeft = new HashMap<LedgerVariant, Long>();
        Map<ItemKey, Long> identityLeft = new HashMap<ItemKey, Long>();
        for (Source source : sources) {
            ItemStack stack = source.peek();
            if (stack.isEmpty() || usableAsIngredient(stack, allowProtected) || !acceptedByAny(ingredients, stack)) {
                continue;
            }
            LedgerVariant variant = variantOf(stack);
            ItemKey identity = ledgerKey(stack);
            Long forVariant = variantLeft.get(variant);
            if (forVariant == null) {
                forVariant = tally.variantHeadroom(variant);
            }
            Long forIdentity = identityLeft.get(identity);
            if (forIdentity == null) {
                forIdentity = tally.identityHeadroom(identity);
            }
            int add = (int) Math.min(stack.getCount(), Math.min(forVariant, forIdentity));
            if (add > 0) {
                Integer have = stock.get(identity);
                stock.put(identity, NetworkTerminals.saturatedAdd(have == null ? 0 : have, add));
            }
            variantLeft.put(variant, forVariant - Math.max(add, 0));
            identityLeft.put(identity, forIdentity - Math.max(add, 0));
        }
    }

    /** Whether any non-empty ingredient of the recipe accepts {@code stack}. */
    private static boolean acceptedByAny(List<Ingredient> ingredients, ItemStack stack) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient != null && ingredient != Ingredient.EMPTY && ingredient.apply(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A plain stack of {@code preferred} ({@link #preferredKeys}) when one matches, then (with a
     * tally, #26 pass only) a refused copy of it the tally admits, then — a protected-planned job
     * only, from the player's main slots or the network's stored identities (#40, #49) — a
     * protected copy of it, all before any alternative
     * identity, which a later cell may need alone; otherwise the first plain match in pool order,
     * then (a protected-planned job only) any protected stack the ingredient accepts, and last
     * (with a ledger) any refused stack the tally admits. That is the #40 pick order: a plain copy
     * of the needed item always wins, from whichever source holds it.
     */
    private static int findMatchingSource(List<Source> sources, Ingredient ingredient, ItemKey preferred,
                                          int[] reserved, LedgerTally<ItemKey, LedgerVariant> tally,
                                          boolean admittedPreference, boolean allowProtected) {
        if (preferred != null) {
            int plain = findPreferredPlainSource(sources, ingredient, preferred, reserved);
            if (plain >= 0) {
                return plain;
            }
            if (tally != null && admittedPreference) {
                // The job's altered copy of the identity the rule chose (#26), before any alternative
                // a later cell may need alone. Matched by ledgerKey: the plan credited the fresh output,
                // whose key is its ledger identity, while an altered damageable copy's own key carries
                // its damage. Protected stacks are not ledger material for a job planned with them
                // (they have their own, earlier tier), so the widened predicate never matches here.
                for (int i = 0; i < sources.size(); i++) {
                    ItemStack stack = sources.get(i).peek();
                    if (stack.isEmpty() || stack.getCount() - reserved[i] <= 0
                            || usableAsIngredient(stack, allowProtected)) {
                        continue;
                    }
                    ItemKey key = ledgerKey(stack);
                    if (preferred.equals(key) && tally.admits(key, variantOf(stack)) && ingredient.apply(stack)) {
                        return i;
                    }
                }
            }
            if (allowProtected) {
                // The plan's own item as a protected copy (#40), after its plain copies in every
                // source but before any alternative identity: the plan may have given a plain
                // alternative to a later cell that accepts nothing else, and taking it here would
                // starve that cell.
                int slot = findProtectedSource(sources, ingredient, preferred, reserved);
                if (slot >= 0) {
                    return slot;
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
        if (allowProtected) {
            // The protected tier (#40): the player's confirmed special copies, taken only after
            // every plain stack in the pool could not fill the cell.
            int slot = findProtectedSource(sources, ingredient, null, reserved);
            if (slot >= 0) {
                return slot;
            }
        }
        if (tally == null) {
            return -1;
        }
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).peek();
            if (stack.isEmpty() || stack.getCount() - reserved[i] <= 0
                    || usableAsIngredient(stack, allowProtected)) {
                continue;
            }
            if (tally.admits(ledgerKey(stack), variantOf(stack)) && ingredient.apply(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The first unreserved plain stack of {@code preferred} in pool order that the ingredient
     * accepts, or -1: tier 1 of {@link #findMatchingSource}'s pick order, and the one rule a batch
     * repeats k times — a pick any later tier makes depends on more than the pool's item counts
     * (issue #35).
     */
    private static int findPreferredPlainSource(List<Source> sources, Ingredient ingredient, ItemKey preferred,
                                                int[] reserved) {
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).peek();
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && preferred.equals(ItemKey.of(stack))
                    && usableAsIngredient(stack) && ingredient.apply(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The first unreserved protected stack ({@link #isProtected}) the ingredient accepts — of
     * {@code preferred} when non-null, else of any identity — or -1. The player's main-slot sources
     * are scanned first, then — with a network crafting terminal open — the network's stored
     * identities, one source each (issue #49): the #40 tier is the player's confirmed special
     * copies, extended to the ones the open terminal displays, reached only by a job planned with
     * protected stacks and only after every plain stack and the station's stock fell short, so a
     * plain copy of the needed item always wins.
     */
    private static int findProtectedSource(List<Source> sources, Ingredient ingredient, ItemKey preferred,
                                           int[] reserved) {
        for (int i = 0; i < sources.size(); i++) {
            if (!(sources.get(i) instanceof PlayerSource)) {
                continue;
            }
            ItemStack stack = sources.get(i).peek();
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && isProtected(stack)
                    && (preferred == null || preferred.equals(ItemKey.of(stack))) && ingredient.apply(stack)) {
                return i;
            }
        }
        // The network's protected identities come last (#49): buildSources lists them after every
        // player and external source, so this second scan is the tier's final fallback.
        for (int i = 0; i < sources.size(); i++) {
            if (!(sources.get(i) instanceof NetworkSource)) {
                continue;
            }
            ItemStack stack = sources.get(i).peek();
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && isProtected(stack)
                    && (preferred == null || preferred.equals(ItemKey.of(stack))) && ingredient.apply(stack)) {
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

        /**
         * Removes {@code count} items at once — a batch's drain (issue #35); the same pool state as
         * that many {@link #consumeOne} calls, without paying per-item call overhead on a
         * million-craft step.
         */
        default void consume(int count) {
            for (int i = 0; i < count; i++) {
                consumeOne();
            }
        }

        /** How many of {@code want} a real take would deliver; only a station slot can say fewer than it holds. */
        default int supplyable(int want) {
            return Math.min(want, peek().getCount());
        }

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
        public void consume(int count) {
            ItemStack stack = inventory.mainInventory.get(slot);
            stack.shrink(count);
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
            consume(1);
        }

        /**
         * What a real take of {@code want} would hand over (#52). A handler-backed slot can refuse
         * or cap an extract while its stack still reads full, so it is asked with a simulated
         * extract; a plain inventory slot always gives what it holds. The craft paths check this
         * for every cell before consuming anything, so a short slot costs nothing.
         */
        @Override
        public int supplyable(int want) {
            if (slot instanceof SlotItemHandler) {
                SlotItemHandler handlerSlot = (SlotItemHandler) slot;
                return handlerSlot.getItemHandler().extractItem(handlerSlot.getSlotIndex(), want, true).getCount();
            }
            return Math.min(want, peek().getCount());
        }

        @Override
        public void consume(int count) {
            int left = count;
            while (left > 0) {
                ItemStack got = slot.decrStackSize(left);
                if (got.isEmpty() || got.getCount() <= 0) {
                    // Unreachable unless the handler's real extract disagrees with the simulated one
                    // the preflight read. Warn, never throw: a throw here escapes the tick and stops
                    // the server, losing the cells this craft already consumed.
                    LogManager.getLogger("SprawlCrafting").warn(
                            "Station slot {} gave {} of {} although its simulated extract allowed it",
                            slot.slotNumber, count - left, count);
                    return;
                }
                left -= got.getCount();
            }
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
                // Unreachable by construction: only the craft paths build network sources, and they
                // prepare every pick before consuming. Never let a miss pass silently all the same.
                LogManager.getLogger("SprawlCrafting")
                        .warn("Network source consumed without a prepared item: {}", template);
            }
            view.shrink(1);
        }

        @Override
        public void consume(int count) {
            int fromPrepared = Math.min(count, prepared);
            prepared -= fromPrepared;
            int rest = count - fromPrepared;
            if (rest > 0 && network.extract(template, rest, false) < rest) {
                // As in consumeOne: a batch extracts its whole share up front, so this too is
                // unreachable by construction — and never silent.
                LogManager.getLogger("SprawlCrafting")
                        .warn("Network source consumed without a prepared item: {}", template);
            }
            view.shrink(count);
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

    /**
     * Vanilla recipe-book parity: a plain stack (not damaged, enchanted, or renamed). Enchanted or
     * renamed stacks are still consumed by a job the player confirmed for them (issue #40; see
     * {@link #usableAsIngredient(ItemStack, boolean)}); damaged stacks never are, beyond the ledger.
     */
    public static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isItemDamaged() && !stack.isItemEnchanted() && !stack.hasDisplayName();
    }

    /**
     * {@link #usableAsIngredient} widened by the job's protected tier (issue #40): with
     * {@code allowProtected} (a job planned with protected stacks, after the player confirmed), a
     * {@link #isProtected} stack counts as material too — the plan already counted it, so every
     * executor count and pick the plan relies on must accept it as well, or the executor would
     * disagree with the plan and stall. Damaged stacks are never material either way.
     */
    public static boolean usableAsIngredient(ItemStack stack, boolean allowProtected) {
        return usableAsIngredient(stack) || (allowProtected && isProtected(stack));
    }

    /**
     * A protected stack (issue #40): not damaged, but enchanted and/or renamed. Some mods only ever
     * hand out such copies of an intermediate (enchanted + renamed, Unbreakable, never damaged), so
     * recipes needing it can be planned at all only by counting them — which is why they are a
     * separate, player-confirmed tier and not plain material: a plain stack of the same item always
     * wins, both at planning and at every pick. Damaged stacks are never protected.
     */
    public static boolean isProtected(ItemStack stack) {
        return !stack.isEmpty() && !stack.isItemDamaged() && (stack.isItemEnchanted() || stack.hasDisplayName());
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
            noteDrop(stack);
            player.dropItem(stack, false);
        }
        return stored;
    }

    /** The first stack dropped at the feet since the last {@link #takeDropped}; server thread only. */
    private static ItemStack firstDrop = ItemStack.EMPTY;

    /**
     * Records the first stack an {@link #insertIntoMainInventory} dropped at the player's feet since
     * the last {@link #takeDropped}; later drops leave it alone — one is enough to trip the wire.
     * Every overflow path shares the record (result, remainders, network refunds): any drop during
     * a job's craft says the same thing, that nothing had room for it.
     */
    private static void noteDrop(ItemStack overflow) {
        if (!firstDrop.isEmpty()) {
            return;
        }
        firstDrop = overflow.copy();
    }

    /**
     * The first stack dropped at the player's feet since the last call (then forgotten), or
     * {@link ItemStack#EMPTY}. The queue manager clears this before each craft and reads it after
     * (issue #45): a bulk craft whose output dropped stops the job there, so a full inventory costs
     * one craft's spill, never a spill repeated every craft of the burst.
     */
    static ItemStack takeDropped() {
        ItemStack dropped = firstDrop;
        firstDrop = ItemStack.EMPTY;
        return dropped;
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

    /**
     * How many of {@code stack} the 36 main slots take now (merge room plus empty slots, capped at
     * the stack's count): the share {@link #insertIntoMainInventory} places before offering the
     * rest to an open network.
     */
    static int mainInventoryRoom(InventoryPlayer inventory, ItemStack stack) {
        long room = 0;
        for (int i = 0; i < MAIN && room < stack.getCount(); i++) {
            ItemStack existing = inventory.mainInventory.get(i);
            if (existing.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (sameItem(existing, stack)) {
                room += Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return (int) Math.min(room, stack.getCount());
    }

    /**
     * Room for copies of {@code stack} in the 36 main slots: the free space of sameItem stacks and,
     * when {@code emptySlots}, one max stack per empty slot. Read before any consumption moves
     * anything — {@link #batchPlan}'s view, deliberately an underestimate (slots the batch's own
     * consumption frees are not counted) so what it bounds can never spill; the network's share is
     * the caller's to prove separately.
     */
    private static long inventoryRoom(InventoryPlayer inventory, ItemStack stack, boolean emptySlots) {
        long room = 0;
        int max = Math.max(1, stack.getMaxStackSize());
        for (int i = 0; i < MAIN; i++) {
            ItemStack existing = inventory.mainInventory.get(i);
            if (existing.isEmpty()) {
                if (emptySlots) {
                    room += max;
                }
            } else if (sameItem(existing, stack)) {
                room += Math.max(0, max - existing.getCount());
            }
        }
        return room;
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
