package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.legoj15.solver.LedgerTally;
import com.legoj15.solver.ProducedLedger;
import com.legoj15.solver.SlotAssignment;
import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.RecipeBookContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ICraftingRecipe;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapedRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;

/**
 * Performs a single craft of a recipe directly against a player's live inventory: consumes one item per
 * ingredient slot, returns crafting remainders (empty buckets), and inserts the result as real items
 * (overflow drops at the player's feet). This is the per-step consumption point of DESIGN.md.
 *
 * <p>Two invariants shared with {@code CraftPlanner}: only the 36 main inventory slots are read and
 * written (never armor or offhand) — plus, while one is open and usable, a crafting station's storage
 * ({@link StationStorages}: the AE2 Crafting Terminal's ME network), which both the planner and
 * {@link #craftOnce} add to the pool — and damaged/enchanted/renamed stacks are never consumed, matching
 * what the vanilla recipe book refuses to auto-fill, except up to what the job's {@link ProducedLedger}
 * says it produced itself (some mods enchant an item on its first inventory tick, after insertion), and
 * — only for a job planned with them after the player confirmed (issue #40) — the protected stacks
 * {@link #isProtected} describes: per cell, a protected copy of the plan's item comes after its plain
 * copies (inventory, then station) but before any alternative item, which a later cell may need alone. The ledger's player-own baseline is a read-only exception to the first: it counts the filter-rejected
 * stacks the player holds outside the main slots too (see {@link #countRefused}), never picked from.
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    private static final int MAIN_SLOTS = CraftPlanner.MAIN_SLOTS;

    /** Outcome of one craft. Java 8 stand-in for the modern sealed interface. */
    public static final class CraftResult {
        public enum Kind { SUCCESS, MISSING_INGREDIENT, RECIPE_GONE, NO_ROOM }

        private final Kind kind;
        private final ItemStack crafted;
        private final ITextComponent missing;
        private final boolean usedStation;
        private final int crafts;

        private CraftResult(Kind kind, ItemStack crafted, ITextComponent missing, boolean usedStation,
                            int crafts) {
            this.kind = kind;
            this.crafted = crafted;
            this.missing = missing;
            this.usedStation = usedStation;
            this.crafts = crafts;
        }

        static CraftResult missing(ITextComponent missing) {
            return new CraftResult(Kind.MISSING_INGREDIENT, ItemStack.EMPTY, missing, false, 0);
        }

        static CraftResult recipeGone() {
            return new CraftResult(Kind.RECIPE_GONE, ItemStack.EMPTY, null, false, 0);
        }

        static CraftResult noRoom(ITextComponent what) {
            return new CraftResult(Kind.NO_ROOM, ItemStack.EMPTY, what, false, 0);
        }

        public Kind kind() {
            return kind;
        }

        /** Display copy of what was produced ({@link Kind#SUCCESS}). */
        public ItemStack crafted() {
            return crafted;
        }

        /**
         * The item this failure names: the vanished ingredient ({@link Kind#MISSING_INGREDIENT}) or
         * the output that had no room ({@link Kind#NO_ROOM}).
         */
        public ITextComponent missing() {
            return missing;
        }

        /**
         * Whether this successful craft drew any ingredient from, or stored overflow in, an open crafting
         * station's storage ({@link StationStorages}).
         */
        public boolean usedStation() {
            return usedStation;
        }

        /** How many crafts this result applied: 1 per {@link #craftOnce}, k for a batch, 0 for failures. */
        public int crafts() {
            return crafts;
        }
    }

    public static final class FillOutcome {
        /** FILLED: the grid shows the result to grab; FALLBACK: caller auto-crafts; RECIPE_GONE: recipe vanished. */
        public enum Kind { FILLED, FALLBACK, RECIPE_GONE }

        private final Kind kind;
        private final ItemStack result;

        private FillOutcome(Kind kind, ItemStack result) {
            this.kind = kind;
            this.result = result;
        }

        static FillOutcome fallback() {
            return new FillOutcome(Kind.FALLBACK, ItemStack.EMPTY);
        }

        public Kind kind() {
            return kind;
        }

        public ItemStack result() {
            return result;
        }
    }

    /**
     * One craft against the live inventory and, when the player has a usable crafting station open, its
     * storage. {@code stationPlanned} says the job's plan counted on that storage
     * ({@link CraftJob#stationDependent()}): its crafts are assigned over inventory plus station stock,
     * exactly as the plan chose. Any other job is assigned from the inventory alone first, so it never
     * touches the network while its own items cover the craft, and reaches into the station only for what
     * the inventory has since lost (the player moved items mid-job). The exception is a protected job
     * (issue #40) with a station open: it is pooled from the start, so the station's plain stock is
     * drawn before the player's protected copies. {@link CraftResult#usedStation()}
     * reports that, so the job pauses rather than cancels once the station goes away.
     *
     * <p>{@code layout} is the plan's per-slot item choice for this craft
     * ({@link CraftJob#currentCraftLayout()}); null or a layout the live pool can no longer honor falls
     * back to {@link SlotAssignment}.
     *
     * <p>{@code refuseSpill} is the bulk-step rule: when true, a craft whose result or remainders would
     * not all fit (inventory — including slots this craft's own consumption frees — plus an open
     * station's storage; see {@link #spilledOutput}) is refused as {@link CraftResult.Kind#NO_ROOM}
     * before anything moves, instead of dropping the overflow at the player's feet thousands of times
     * per tick. A paced step passes false: a few items at the feet is fine and expected there.
     *
     * <p>{@code allowProtected} is the job's {@code CraftJob#usesProtected()} flag: a job planned with
     * the player's protected stacks (issue #40) may also pick those: per cell, after the plan's item as a
     * plain inventory or station copy, but before any alternative item (see {@link #assignPass}). Every
     * other job keeps the plain rule exactly.
     */
    public static CraftResult craftOnce(ServerPlayerEntity player, ResourceLocation recipeId,
                                        ProducedLedger<Item, LedgerVariant> ledger, boolean stationPlanned,
                                        List<Item> layout, boolean refuseSpill, boolean allowProtected) {
        // Phase 1: look the recipe up and match every ingredient slot to an inventory slot (or a
        // crafting station's storage) without mutating, so a shortfall discovered at slot N leaves
        // slots 1..N-1 untouched.
        Plan plan = prepare(player, recipeId, ledger, stationPlanned, layout, allowProtected);
        if (plan == null) {
            return CraftResult.recipeGone();
        }
        return applyOnce(player, plan, refuseSpill);
    }

    /** {@link #craftOnce} from an already {@link #prepare}d plan (the batch's fallback reuses its own). */
    private static CraftResult applyOnce(ServerPlayerEntity player, Plan plan, boolean refuseSpill) {
        ProducedLedger<Item, LedgerVariant> ledger = plan.ledger;
        Assignment assignment = plan.assignment;
        if (assignment.missing != null) {
            return CraftResult.missing(describe(assignment.missing));
        }
        if (refuseSpill) {
            ITextComponent spilled = spilledOutput(player, plan);
            if (spilled != null) {
                return CraftResult.noRoom(spilled);
            }
        }
        PlayerInventory inventory = player.inventory;
        StationStorage station = plan.station;

        // Phase 2a: the station's share first, all or nothing. Its storage can change under us (another
        // player or automation drained it since the simulated check): a short extraction gives back
        // everything this craft drew and reports the item missing, before the inventory is touched.
        Map<Item, Integer> stationTakes = assignment.stationTakes;
        if (!stationTakes.isEmpty()) {
            Item shortItem = drawFromStation(player, station, stationTakes);
            if (shortItem != null) {
                return CraftResult.missing(new ItemStack(shortItem).getHoverName());
            }
        }

        // Phase 2b: apply — consume, return remainders, insert the result. The remainder lookups are mod
        // code and can throw; the station's share is already out of its storage by now, so on a failure
        // it goes back (station, else inventory, else feet) before the error propagates.
        List<ItemStack> remainders = new ArrayList<>();
        try {
            List<Item> consumed = new ArrayList<>();
            for (int slot : assignment.slots) {
                if (slot < 0) {
                    continue; // a blank cell, or a cell the station supplied (drawn in phase 2a)
                }
                ItemStack stack = inventory.getItem(slot);
                consumed.add(stack.getItem());
                stack.shrink(1);
                if (stack.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
            }
            for (Map.Entry<Item, Integer> take : stationTakes.entrySet()) {
                for (int i = 0; i < take.getValue(); i++) {
                    consumed.add(take.getKey());
                }
            }
            for (Item item : consumed) {
                if (item.hasCraftingRemainingItem()) {
                    remainders.add(new ItemStack(item.getCraftingRemainingItem()));
                }
            }
        } catch (Throwable failure) {
            // Any Throwable, even an Error, as in StationDraw: the drawn share is real either way.
            for (Map.Entry<Item, Integer> take : stationTakes.entrySet()) {
                try {
                    giveBack(player, station, take.getKey(), take.getValue());
                } catch (Throwable giveBackFailure) {
                    failure.addSuppressed(giveBackFailure);
                }
            }
            throw failure;
        }
        inventory.setChanged();
        assignment.chargeTo(ledger);
        // Results and remainders go to the main inventory as always; with a station open, what would
        // otherwise drop at the player's feet goes into its storage first. Overflow stored in the station
        // counts as reliance too: the chain's own item now sits in its storage, so closing the station must
        // pause the job, not cancel it.
        boolean storedInStation = false;
        for (ItemStack remainder : remainders) {
            storedInStation |= insertIntoMainInventory(player, remainder, station);
        }
        ItemStack crafted = plan.result.copy();
        storedInStation |= insertIntoMainInventory(player, plan.result.copy(), station);
        ledger.produced(crafted.getItem(), crafted.getCount());
        return new CraftResult(CraftResult.Kind.SUCCESS, crafted, null, !stationTakes.isEmpty() || storedInStation, 1);
    }

    /**
     * Phase 1 of both craft paths: recipe lookup, station open and the two-pass assignment —
     * everything up to (but not including) the first mutation of inventory or station, so both a
     * single craft and a batch start from the very same assignment a {@link #craftOnce} would make
     * right now. {@code layout} is that craft's plan layout (null: none), honoured or fallen back
     * from exactly as craftOnce would. Returns null when the recipe is gone; otherwise the
     * assignment's {@code missing} carries the first ingredient nothing could satisfy, if any.
     */
    private static Plan prepare(ServerPlayerEntity player, ResourceLocation recipeId,
                                ProducedLedger<Item, LedgerVariant> ledger, boolean stationPlanned,
                                List<Item> layout, boolean allowProtected) {
        IRecipe<?> recipe = RecipeIds.byId(player.getServer().getRecipeManager(), recipeId).orElse(null);
        if (!(recipe instanceof ICraftingRecipe)) {
            return null;
        }
        ItemStack result = RecipeIds.resultOf(recipe);
        if (result.isEmpty()) {
            return null;
        }
        PlayerInventory inventory = player.inventory;
        List<Ingredient> ingredients = recipe.getIngredients();
        // A crafting station open right now (AE2 Crafting Terminal) adds its storage to the pool. It is
        // re-opened for every craft, so a closed or offline station stops being drawn from at once.
        final StationStorage station = StationStorages.open(player);
        Assignment assignment = null;
        // A protected job (#40) with a station open goes straight to the pooled pass: its per-cell order
        // (the preferred item from the inventory, then the station, then as a protected copy, before
        // any alternative) is the rule, while an inventory-only pass would either take a protected copy
        // ahead of the station's plain one or, kept plain, reject the plan's layout and substitute an
        // alternative a later step needs.
        if ((station == null || !stationPlanned) && !(allowProtected && station != null)) {
            assignment = assign(inventory, ingredients, ledger, null, layout, allowProtected);
            if (assignment.missing != null && station != null) {
                assignment = null; // the inventory alone fell short: let the open station supply the rest
            }
        }
        if (assignment == null) {
            assignment = assign(inventory, ingredients, ledger, station, layout, allowProtected);
        }
        return new Plan(recipeId, ledger, stationPlanned, layout, result, ingredients, station, assignment,
                allowProtected);
    }

    /** What {@link #prepare} looked up: the inputs plus the assignment both craft paths start from. */
    private static final class Plan {
        final ResourceLocation recipeId;
        final ProducedLedger<Item, LedgerVariant> ledger;
        final boolean stationPlanned;
        final List<Item> layout;
        final ItemStack result;
        final List<Ingredient> ingredients;
        final StationStorage station;
        final Assignment assignment;
        /** The job's protected flag: its assignment may pick protected stacks (see {@link #isProtected}). */
        final boolean allowProtected;

        Plan(ResourceLocation recipeId, ProducedLedger<Item, LedgerVariant> ledger, boolean stationPlanned,
             List<Item> layout, ItemStack result, List<Ingredient> ingredients,
             StationStorage station, Assignment assignment, boolean allowProtected) {
            this.recipeId = recipeId;
            this.ledger = ledger;
            this.stationPlanned = stationPlanned;
            this.layout = layout;
            this.result = result;
            this.ingredients = ingredients;
            this.station = station;
            this.assignment = assignment;
            this.allowProtected = allowProtected;
        }
    }

    /**
     * Up to {@code maxCrafts} crafts of the recipe as ONE operation, leaving the same per-item totals
     * (inventory plus station), ledger state and drops (none) as that many successive
     * {@link #craftOnce} calls would; with a station open, outputs can be split differently between the
     * inventory and the station (see {@link #batchPlan}). One recipe
     * lookup, one station open, one assignment scan, one station draw and one insert per output
     * instead of one of each per craft. The batch applies the very assignment {@link #prepare} just
     * made {@code k} times, which is only provably the same as k single crafts while
     * {@link SlotAssignment#stableRepeats} says the choice holds and every cell took its preferred
     * item; any case that cannot be proven — a fallback pick, a ledger-held candidate stack,
     * remainders with no station open, output that would not fit — runs a single craft (k = 1) on
     * the plan already prepared, with exactly craftOnce's result, message and partial progress.
     * That fallback (and the batch's own short-draw fallback into {@link #craftOnce}) applies the
     * {@code refuseSpill} check like a plain bulk-step craftOnce would.
     *
     * <p>{@code allowProtected} is the job's {@code CraftJob#usesProtected()} flag (see
     * {@link #craftOnce}); a protected-planned batch counts, consumes and charges protected stacks like
     * plain material, so the repetition proof holds for them too.
     */
    public static CraftResult craftBatch(ServerPlayerEntity player, ResourceLocation recipeId,
                                         ProducedLedger<Item, LedgerVariant> ledger, boolean stationPlanned,
                                         List<Item> layout, int maxCrafts, boolean refuseSpill,
                                         boolean allowProtected) {
        Plan plan = prepare(player, recipeId, ledger, stationPlanned, layout, allowProtected);
        if (plan == null) {
            return CraftResult.recipeGone();
        }
        if (plan.assignment.missing != null) {
            return CraftResult.missing(describe(plan.assignment.missing));
        }
        Batch batch = batchPlan(player, plan, maxCrafts);
        if (batch == null) {
            return applyOnce(player, plan, refuseSpill); // nothing has moved yet: the plan is still this craft's
        }
        return applyBatch(player, plan, batch, refuseSpill);
    }

    /** A batched craft worked out ahead of applying it. */
    private static final class Batch {
        /** How many crafts this batch applies (at least 2). */
        final int crafts;
        /** Per-item consumption of ONE craft (one of every non-blank cell's preferred item). */
        final Map<Item, Long> use;
        /** Per-craft crafting remainders, by item. */
        final Map<Item, Long> remainders;
        /** Usable counts of the used items in the 36 main slots, read before anything moved. */
        final Map<Item, Long> usableInventory;
        /** The station's share of the whole batch (empty when the inventory covers it all). */
        final Map<Item, Integer> stationTakes;

        Batch(int crafts, Map<Item, Long> use, Map<Item, Long> remainders,
              Map<Item, Long> usableInventory, Map<Item, Integer> stationTakes) {
            this.crafts = crafts;
            this.use = use;
            this.remainders = remainders;
            this.usableInventory = usableInventory;
            this.stationTakes = stationTakes;
        }
    }

    /**
     * Works out the batch of up to {@code maxCrafts} crafts, or null when this call may not batch
     * (k = 1: a plain {@link #craftOnce}). k is the minimum of the caller's ceiling, the supply of
     * every ingredient, {@link SlotAssignment#stableRepeats} over the assignment's own stock view,
     * int safety and — with no station open to catch overflow — the room the result has in the 36
     * main slots. Every cap is chosen so the batch's final state is exactly k single crafts'.
     */
    private static Batch batchPlan(ServerPlayerEntity player, Plan plan, int maxCrafts) {
        if (maxCrafts <= 1 || !plan.assignment.preferredFilled) {
            return null; // a fallback pick (any usable stack, any station alternative, a ledger-admitted
            // refused stack) makes the per-craft path's picks order-dependent; do not batch them.
        }
        Assignment assignment = plan.assignment;
        PlayerInventory inventory = player.inventory;
        // One craft consumes one of every non-blank cell's preferred item (eligibility checked each
        // cell got exactly that) and is credited the result plus one remainder per consumed cell.
        Map<Item, Long> use = new LinkedHashMap<>();
        List<List<Item>> slots = new ArrayList<>();
        Set<Item> candidates = new HashSet<>();
        for (int n = 0; n < plan.ingredients.size(); n++) {
            Ingredient ingredient = plan.ingredients.get(n);
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            List<Item> alternatives = CraftPlanner.slotAlternatives(ingredient);
            slots.add(alternatives);
            candidates.addAll(alternatives);
            use.merge(assignment.preferred[n], 1L, Long::sum);
        }
        // A layout the plan carried must be the one this assignment honoured — the same check
        // {@link #preferredItems} made, re-evaluated over the same stock snapshot it chose on
        // (nothing has moved since). The per-craft path re-runs that check before every craft, so a
        // later craft whose stock has drifted back over the layout's use would switch to the
        // layout's items while the batch keeps repeating the fallback's assignment, a re-check
        // {@link SlotAssignment#stableRepeats} does not model. A null layout (a step without one)
        // picks with {@code SlotAssignment} alone and keeps the plain repetition proof.
        if (plan.layout != null
                && !(plan.layout.size() == slots.size() && layoutFits(plan.layout, slots, assignment.stock))) {
            return null;
        }
        ItemStack result = plan.result;
        Map<Item, Long> remainders = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : use.entrySet()) {
            if (entry.getKey().hasCraftingRemainingItem()) {
                remainders.merge(entry.getKey().getCraftingRemainingItem(), entry.getValue(), Long::sum);
            }
        }
        Map<Item, Long> made = new LinkedHashMap<>(remainders);
        made.merge(result.getItem(), (long) result.getCount(), Long::sum);
        // A craft credited an item it also consumes (its own result in a slot, or a remainder equal to a
        // candidate) cannot batch: each single craft applies and inserts its output BEFORE the next craft
        // assigns, so craft i draws stock (and charges the ledger) that craft i-1 just put back — an
        // interleave one counted application cannot reproduce exactly.
        for (Item credited : made.keySet()) {
            if (use.containsKey(credited)) {
                return null;
            }
        }
        // No refused stack of a candidate item — or of any item a cell's ingredient would still
        // test-accept (a modded ingredient can accept more than its listed alternatives) — may sit
        // in the main slots. With one there the ledger holds counts against items the batch
        // consumes, and both ledger-only ways an assignment stops provably repeating open up: a
        // pick of an admitted altered variant draws that variant's headroom down one craft at a
        // time, and which variant a pick takes is not derivable from item counts alone; and the #26
        // preference rule counts exactly those stacks into the stock this assignment was chosen
        // over ({@link #countAdmitted}), so a choice fed by headroom — a layout honoured against
        // it, or the first assign pass failing into the second, which differs only through that
        // stock — cannot be repeated from item totals. With none of them the stock
        // {@link SlotAssignment#stableRepeats} reads is plain inventory plus station, every pick is
        // plain, and one counted charge of k*m equals k charges of m: {@code chargeTo}'s plain
        // drain is min(k*m, outstanding less keep), exactly the total of k per-craft drains of m
        // against the same frozen keep. A protected-planned job's protected stacks are plain
        // material in exactly that sense ({@link #assignPass} records their picks as plain and
        // {@link #countRefused} keeps them out of the ledger's refused baseline), so they do not
        // block batching; damaged stacks always do. (The lookups above ran before anything was
        // drawn, so a throwing remainder mod cannot strand items here.)
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !usableAsIngredient(stack, plan.allowProtected)
                    && (candidates.contains(stack.getItem()) || acceptedByAny(plan.ingredients, stack))) {
                return null;
            }
        }
        // The batch consumes a preferred item from any usable stack of it, by item alone; a single craft
        // also asks the cell's ingredient (findPreferredSlot), which an NBT-matching ingredient can refuse
        // for some stacks of the same item. Batch only when every such stack passes every cell taking it.
        for (int n = 0; n < plan.ingredients.size(); n++) {
            Ingredient ingredient = plan.ingredients.get(n);
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            for (int i = 0; i < MAIN_SLOTS; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == assignment.preferred[n]
                        && usableAsIngredient(stack, plan.allowProtected)
                        && !ingredient.test(stack)) {
                    return null;
                }
            }
        }
        long k = SlotAssignment.stableRepeats(slots, item -> count(assignment.stock, item), use, made, maxCrafts);
        Map<Item, Long> usableInventory = new LinkedHashMap<>();
        Map<Item, Long> plainInventory = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : use.entrySet()) {
            Item item = entry.getKey();
            long perCraft = entry.getValue();
            // The station's stock counts only when its pool was part of this assignment, so a batch of
            // a non-station-planned job the inventory covers never touches the network. The limit is
            // Integer.MAX_VALUE (StationPool.count's precedent): the int caps below bound k*perCraft
            // anyway, so anything a bigger limit could report is never reachable.
            long inInventory = usableCount(inventory, item, plan.allowProtected);
            plainInventory.put(item, usableCount(inventory, item, false));
            long available = inInventory;
            if (assignment.stationPooled) {
                available += plan.station.available(item, Integer.MAX_VALUE);
            }
            usableInventory.put(item, inInventory);
            k = Math.min(k, available / perCraft);
            // Station draws and the ledger's counted picks are int-sized.
            k = Math.min(k, Integer.MAX_VALUE / perCraft);
        }
        int perResult = Math.max(1, result.getCount());
        k = Math.min(k, Integer.MAX_VALUE / perResult);
        for (long perCraft : remainders.values()) {
            k = Math.min(k, Integer.MAX_VALUE / perCraft); // summed remainders are inserted as one int stack
        }
        if (plan.station == null && !remainders.isEmpty()) {
            // Remainders could drop at the feet (no station is open to catch them); one craft keeps
            // that the per-craft case.
            return null;
        }
        // Output room: a batch never plans to drop anything at the feet (single crafts would spread such
        // drops over many ticks; a batch would land them all in one). Room is the inventory's, read before
        // the consumption that frees slots (conservative), plus what an open station says it would take.
        // Each output's room must be its own: empty slots and the station's capacity count for the
        // result only (two simulated inserts would both be told about the same free space), so a
        // remainder is bounded by the free space of its own partial stacks in the inventory.
        // With a station, outputs may end up split differently between inventory and station than single
        // crafts would leave them (a single craft overflows before the next one frees a slot); the
        // per-item totals across the two are the same, and nothing is dropped or lost.
        k = Math.min(k, outputRoom(inventory, plan.station, result, k * perResult, true, null) / perResult);
        for (Map.Entry<Item, Long> remainder : remainders.entrySet()) {
            long perCraft = remainder.getValue();
            k = Math.min(k, outputRoom(inventory, null, new ItemStack(remainder.getKey()),
                    k * perCraft, false, null) / perCraft);
        }
        if (remainders.containsKey(result.getItem())) {
            return null; // result and remainder would both count the same partial stacks as room
        }
        if (k <= 1) {
            return null;
        }
        int crafts = (int) k;
        Map<Item, Integer> stationTakes = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> entry : use.entrySet()) {
            // The per-cell order takes: the inventory's plain stacks, then (a pooled station's) plain
            // stock, then — only a protected-planned job — the inventory's protected copies. So the
            // station's share is what the plain inventory cannot supply, and protected copies fill only
            // what is left; the caps above guarantee the pool covers the whole batch either way.
            long need = (long) crafts * entry.getValue();
            long covered = Math.min(plainInventory.get(entry.getKey()), need);
            if (assignment.stationPooled && covered < need) {
                long fromStation = Math.min(plan.station.available(entry.getKey(), Integer.MAX_VALUE),
                        need - covered);
                stationTakes.put(entry.getKey(), (int) fromStation);
            }
        }
        return new Batch(crafts, use, remainders, usableInventory, stationTakes);
    }

    /**
     * How many items the 36 main slots hold in stacks {@link #usableAsIngredient} accepts — plus, for
     * a protected-planned job ({@code allowProtected}), its protected stacks, which the plan counted.
     */
    private static long usableCount(PlayerInventory inventory, Item item, boolean allowProtected) {
        long count = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item && usableAsIngredient(stack, allowProtected)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Room for up to {@code needed} of {@code stack} (never reports more): the free space of same
     * item+tag stacks in the 36 main slots, a full stack per empty slot when {@code emptySlots}, then
     * what {@code station} (null for none) says it would accept of the rest. Read before any
     * consumption moves anything; {@code takes} (null for none) is how many items this one craft's
     * consumption will draw from each main slot first, so a slot it empties already counts as an
     * empty one and a stack it shrinks lends its leftover free space — the single-craft spill check's
     * view ({@link #spilledOutput}), whose insert happens after the consume. {@code batchPlan} passes
     * null and keeps the plain read-before-consumption view: an underestimate, the safe side for
     * bounding a batch.
     */
    private static long outputRoom(PlayerInventory inventory, StationStorage station, ItemStack stack,
                                   long needed, boolean emptySlots, int[] takes) {
        long room = 0;
        int max = Math.max(1, stack.getMaxStackSize());
        for (int i = 0; i < MAIN_SLOTS && room < needed; i++) {
            ItemStack existing = inventory.getItem(i);
            int left = existing.isEmpty() ? 0 : existing.getCount() - (takes == null ? 0 : takes[i]);
            if (left <= 0) {
                room += emptySlots ? max : 0;
            } else if (sameItemSameTag(existing, stack)) {
                room += Math.max(0, max - left);
            }
        }
        if (room < needed && station != null) {
            try {
                room += station.acceptable(stack, needed - room);
            } catch (RuntimeException | LinkageError e) {
                Constants.LOG.warn("SprawlCrafting: crafting station insert check failed, not batching into it", e);
            }
        }
        return Math.min(room, needed);
    }

    /**
     * The bulk-step spill check behind {@code refuseSpill} (see {@link #craftOnce}): the name of the
     * first output one craft of this plan cannot place, or null when the result and every remainder
     * fit. Room is {@link #batchPlan}'s rule at k = 1 — the result gets merge room, empty main slots
     * and an open station's {@code acceptable}; each remainder merge room only — with one addition:
     * the craft consumes before it inserts, so a main slot this craft's own consumption empties
     * already counts as an empty slot (only slots the assignment takes their whole stack from;
     * station takes draw from the station and free no slot). Without it, a full-inventory craft
     * whose consumption frees a slot (logs→planks, no station open) would be refused even though it
     * fits. Remainders may use any empty slot the result does not need, so the empty slots are
     * counted once and handed out result first, matching {@link #applyBatch}'s insert order. The
     * remainder lookups here run before anything has moved, so a throwing remainder mod cannot
     * strand items (as in batchPlan).
     */
    private static ITextComponent spilledOutput(ServerPlayerEntity player, Plan plan) {
        PlayerInventory inventory = player.inventory;
        Assignment assignment = plan.assignment;
        // This craft's inventory takes, per slot: every assignment cell shrinks its slot by one, so a
        // slot goes empty exactly when the takes reach its whole stack.
        int[] takes = new int[MAIN_SLOTS];
        for (int slot : assignment.slots) {
            if (slot >= 0) {
                takes[slot]++;
            }
        }
        long emptySlots = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || takes[i] >= stack.getCount()) {
                emptySlots++;
            }
        }
        // The remainders applyOnce would return (one per consumed item with a remainder, inventory
        // and station takes alike), grouped per variant so two of a kind do not count the same
        // partial stack's room twice. A remainder of the result's own variant shares the result's
        // merge room and empty slots, so it joins the result's need instead.
        List<ItemStack> groups = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        ItemStack result = plan.result;
        long need = result.getCount();
        List<ItemStack> remainders = new ArrayList<>();
        for (int slot : assignment.slots) {
            if (slot >= 0) {
                Item item = inventory.getItem(slot).getItem();
                if (item.hasCraftingRemainingItem()) {
                    remainders.add(new ItemStack(item.getCraftingRemainingItem()));
                }
            }
        }
        for (Map.Entry<Item, Integer> take : assignment.stationTakes.entrySet()) {
            if (take.getKey().hasCraftingRemainingItem()) {
                for (int i = 0; i < take.getValue(); i++) {
                    remainders.add(new ItemStack(take.getKey().getCraftingRemainingItem()));
                }
            }
        }
        for (ItemStack remainder : remainders) {
            if (sameItemSameTag(remainder, result)) {
                need++;
                continue;
            }
            int group = groups.size();
            for (int g = 0; g < groups.size(); g++) {
                if (sameItemSameTag(groups.get(g), remainder)) {
                    group = g;
                    break;
                }
            }
            if (group == groups.size()) {
                groups.add(remainder);
                counts.add(0);
            }
            counts.set(group, counts.get(group) + 1);
        }
        // The result first, through outputRoom's own order: merge room, then empty slots, then the
        // station. When it needed the station at all, every empty slot went into filling it first
        // (insertIntoMainInventory fills the inventory before the station), so nothing is spare.
        if (outputRoom(inventory, plan.station, result, need, true, takes) < need) {
            return result.getHoverName();
        }
        int resultMax = Math.max(1, result.getMaxStackSize());
        long uncovered = need - outputRoom(inventory, null, result, need, false, takes);
        long spare = emptySlots - Math.min(emptySlots, (uncovered + resultMax - 1) / resultMax);
        for (int g = 0; g < groups.size(); g++) {
            ItemStack remainder = groups.get(g);
            long count = counts.get(g);
            int max = Math.max(1, remainder.getMaxStackSize());
            long room = outputRoom(inventory, null, remainder, count, false, takes);
            if (room < count) {
                long fromEmpty = Math.min(spare * max, count - room);
                if (room + fromEmpty < count) {
                    return remainder.getHoverName();
                }
                spare -= (fromEmpty + max - 1) / max;
            }
        }
        return null;
    }

    /** Applies a worked-out batch: the single-craft phases of {@link #craftOnce}, scaled to k crafts. */
    private static CraftResult applyBatch(ServerPlayerEntity player, Plan plan, Batch batch,
                                          boolean refuseSpill) {
        PlayerInventory inventory = player.inventory;
        StationStorage station = plan.station;
        // Phase 2a: the station's share first, all or nothing, exactly as one craft's draw. A short
        // draw has been given back; the per-craft path then reports the item missing itself, so hand
        // the whole craft over to it rather than inventing a batch-specific message.
        Map<Item, Integer> stationTakes = batch.stationTakes;
        if (!stationTakes.isEmpty() && drawFromStation(player, station, stationTakes) != null) {
            return craftOnce(player, plan.recipeId, plan.ledger, plan.stationPlanned, plan.layout,
                    refuseSpill, plan.allowProtected);
        }
        // Phase 2b: apply. The consumption is the same as k per-craft passes: per item, the batch's
        // share drains usable stacks in slot order, the order findPreferredSlot walks. A
        // protected-planned job drains plain stacks of the item first and protected copies second —
        // the order the assignment's tiers take them — so which copies survive is what that many
        // single crafts would leave.
        try {
            for (Map.Entry<Item, Long> entry : batch.use.entrySet()) {
                // The station's share (drawn above) comes off the top; what is left is the inventory's.
                long need = (long) batch.crafts * entry.getValue()
                        - batch.stationTakes.getOrDefault(entry.getKey(), 0);
                long toConsume = Math.min(batch.usableInventory.get(entry.getKey()),
                        Math.max(0, need));
                for (int pass = 0; pass < 2 && toConsume > 0; pass++) {
                    for (int i = 0; i < MAIN_SLOTS && toConsume > 0; i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (stack.isEmpty() || stack.getItem() != entry.getKey()
                                || usableAsIngredient(stack) != (pass == 0)
                                || !usableAsIngredient(stack, plan.allowProtected)) {
                            continue;
                        }
                        int take = (int) Math.min(toConsume, stack.getCount());
                        stack.shrink(take);
                        toConsume -= take;
                        if (stack.isEmpty()) {
                            inventory.setItem(i, ItemStack.EMPTY);
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            // Any Throwable, even an Error, as in craftOnce: the drawn share is real either way.
            for (Map.Entry<Item, Integer> take : stationTakes.entrySet()) {
                try {
                    giveBack(player, station, take.getKey(), take.getValue());
                } catch (Throwable giveBackFailure) {
                    failure.addSuppressed(giveBackFailure);
                }
            }
            throw failure;
        }
        inventory.setChanged();
        // The ledger charge: k crafts' picks counted in one tally, main's variant-aware one.
        // Eligibility left no refused candidate stack, so every pick is plain and one counted
        // charge of k*m equals k charges of m (see batchPlan).
        if (plan.ledger != null) {
            LedgerTally<Item, LedgerVariant> tally = new LedgerTally<Item, LedgerVariant>();
            countRefused(inventory, tally, plan.allowProtected);
            tally.open(plan.ledger);
            for (Map.Entry<Item, Long> entry : batch.use.entrySet()) {
                tally.picked(entry.getKey(), null, false, (int) ((long) batch.crafts * entry.getValue()));
            }
            tally.chargeTo(plan.ledger);
        }
        // Results and remainders go to the main inventory as always; with a station open, what would
        // otherwise drop at the player's feet goes into its storage first. The result goes in before
        // the remainders: batchPlan gave only the result empty slots and station room, so a remainder
        // inserted first could take space the result was promised.
        boolean storedInStation = false;
        ItemStack crafted = plan.result.copy();
        storedInStation |= insertCopies(player, station, plan.result, (long) batch.crafts * crafted.getCount());
        for (Map.Entry<Item, Long> remainder : batch.remainders.entrySet()) {
            storedInStation |= insertCopies(player, station, new ItemStack(remainder.getKey()),
                    (long) batch.crafts * remainder.getValue());
        }
        if (plan.ledger != null) {
            plan.ledger.produced(crafted.getItem(), (long) batch.crafts * crafted.getCount());
        }
        return new CraftResult(CraftResult.Kind.SUCCESS, crafted, null,
                !stationTakes.isEmpty() || storedInStation, batch.crafts);
    }

    /**
     * Inserts {@code count} copies of {@code template} (its item and tag) as ONE oversized stack through
     * {@link #insertIntoMainInventory}: its slot loops split it into normal stacks, the overflow reaches
     * the open station in a single insert, and anything left drops as normal stacks. One call per max
     * stack would cost a station insert per 64 items, tens of thousands in one tick for a
     * million-craft batch. {@code count} fits an int: craftBatch caps k * result count at
     * Integer.MAX_VALUE.
     *
     * @return whether the station took any of it
     */
    private static boolean insertCopies(ServerPlayerEntity player, StationStorage station,
                                        ItemStack template, long count) {
        if (count <= 0) {
            return false;
        }
        return insertIntoMainInventory(player, copyWithCount(template, (int) count), station);
    }

    private static long count(Map<Item, Integer> stock, Item item) {
        Integer have = stock.get(item);
        return have == null ? 0 : have.longValue();
    }

    /**
     * Extracts {@code takes} from the station, all or nothing ({@link StationDraw}). Returns null when every
     * item came out in full; otherwise everything this call extracted has been given back (into the
     * station, else the inventory, else the player's feet: never voided) and the first item that came up
     * short is returned. If the station's mod throws mid-draw, the same give-back runs before the
     * exception propagates.
     */
    private static Item drawFromStation(final ServerPlayerEntity player, final StationStorage station,
                                        Map<Item, Integer> takes) {
        return StationDraw.draw(takes, station::extract, (item, count) -> giveBack(player, station, item, count));
    }

    /** Returns {@code count} plain {@code item} to the station, overflowing into the inventory, then the feet. */
    private static void giveBack(ServerPlayerEntity player, StationStorage station, Item item, int count) {
        if (count <= 0) {
            return;
        }
        // One insert for the whole count (a batch's share can be millions; StationStorage takes it by
        // count); insertIntoMainInventory splits whatever the station refuses into normal stacks.
        ItemStack rest = insertIntoStation(station, new ItemStack(item, count));
        if (!rest.isEmpty()) {
            insertIntoMainInventory(player, rest, null);
        }
    }

    /**
     * {@link StationStorage#insert} that never loses the stack: what the station refuses, or all of it when
     * the station's mod throws, is returned for the caller to place elsewhere (inventory, then feet).
     */
    private static ItemStack insertIntoStation(StationStorage station, ItemStack stack) {
        try {
            return station.insert(stack);
        } catch (RuntimeException | LinkageError e) {
            Constants.LOG.warn("SprawlCrafting: crafting station insert failed, keeping {} with the player", stack, e);
            return stack;
        }
    }

    /**
     * Hands the final craft off to the crafting grid the player has open, instead of auto-crafting it:
     * lays out this recipe's ingredients (from the inventory, plus an open station's storage for what it
     * lacks) so the grid computes the result for the player to grab. For a vanilla recipe-book menu this
     * drives vanilla's own auto-fill ({@code handlePlacement} → {@code ServerRecipePlacer}), which relocates
     * anything in the grid back to the inventory, lays out the recipe, and silently no-ops when the grid
     * can't be cleared. Like vanilla it only fills unlocked recipes there. Success is confirmed by checking
     * the result slot shows this recipe's output; otherwise the caller auto-crafts.
     *
     * <p>Vanilla's placement counts only undamaged, unenchanted, unnamed stacks, so when the recipe needs an
     * intermediate the job crafted that has since been altered (see {@link ProducedLedger}), or a protected
     * stack the job was planned with ({@code allowProtected}, issue #40), the grid is laid
     * out here instead, from the same assignment {@link #craftOnce} would consume. The same manual layout is
     * the only path for a grid that is not a recipe-book menu (the AE2 Crafting Terminal's:
     * {@link CraftingGrids.Grid#recipeBookPlacement()} is false, there is no {@code handlePlacement} and no
     * recipe book to consult), and a grid that is not on screen is never filled.
     *
     * <p>{@code layout} is the plan's per-slot item choice for this final craft
     * ({@link CraftJob#currentCraftLayout()}); null or a layout the live pool can no longer honor
     * falls back to {@link SlotAssignment}. It picks the stacks only on the manual lay-out (it also
     * feeds the probe that decides whether that path is needed); vanilla's recipe-book placement
     * picks its own stacks, which is safe because this is the job's last craft and no later
     * step depends on which alternative it consumes.
     */
    public static FillOutcome tryFillFinalGrid(ServerPlayerEntity player, ResourceLocation recipeId,
                                               ProducedLedger<Item, LedgerVariant> ledger, List<Item> layout,
                                               boolean allowProtected) {
        IRecipe<?> recipe = RecipeIds.byId(player.getServer().getRecipeManager(), recipeId).orElse(null);
        if (!(recipe instanceof ICraftingRecipe)) {
            return new FillOutcome(FillOutcome.Kind.RECIPE_GONE, ItemStack.EMPTY);
        }
        // Vanilla parity: the recipe book only auto-fills recipes the player has unlocked. Applied below only
        // to recipe-book grids; the terminal's grid is laid out by placeFromLedger, which never consults it.
        boolean locked = !player.getRecipeBook().contains(recipe);
        ItemStack expected = RecipeIds.resultOf(recipe);
        if (expected.isEmpty()) {
            return new FillOutcome(FillOutcome.Kind.RECIPE_GONE, ItemStack.EMPTY);
        }
        Container menu = player.containerMenu;
        CraftingGrids.Grid grid = CraftingGrids.find(menu);
        if (grid == null || !grid.visible() || (locked && grid.recipeBookPlacement())) {
            return FillOutcome.fallback();
        }
        List<Ingredient> cells = gridLayout(recipe, grid.width(), grid.height());
        boolean plainFallsShort = cells != null
                && assign(player.inventory, cells, null, null, layout, false).missing != null;
        boolean needsLedger = plainFallsShort
                && assign(player.inventory, cells, ledger, null, layout, false).missing == null;
        // A protected stack vanilla's placement would refuse is just as much ours to lay out as the
        // ledger's altered intermediates; plain stacks alone still hand the grid to vanilla. The
        // ledger stays in this probe so a craft needing one admitted stack AND one protected stack
        // is caught too.
        boolean needsProtected = plainFallsShort && allowProtected
                && assign(player.inventory, cells, ledger, null, layout, true).missing == null;
        if (needsLedger || needsProtected || !grid.recipeBookPlacement()) {
            // No handlePlacement outside a recipe-book menu, so the layout is ours; a recipe that does not
            // fit the grid has nothing to lay out there.
            return cells == null ? FillOutcome.fallback()
                    : placeFromLedger(player, menu, grid, cells, expected, ledger, layout, allowProtected);
        }
        if (!(menu instanceof RecipeBookContainer)) {
            return FillOutcome.fallback();
        }
        ((RecipeBookContainer<?>) menu).handlePlacement(false, recipe, player);

        ItemStack result = grid.result().getItem();
        if (result.isEmpty() || !ItemStack.isSame(result, expected)) {
            return FillOutcome.fallback();
        }
        // We mutated the menu outside any client click, from the end-of-tick hook (after this tick's
        // broadcastChanges already ran). Push every slot now so the grid renders complete immediately.
        syncWholeMenu(player, menu);
        return new FillOutcome(FillOutcome.Kind.FILLED, result.copy());
    }

    private static void syncWholeMenu(ServerPlayerEntity player, Container menu) {
        menu.broadcastChanges();
        player.refreshContainer(menu, menu.getItems());
    }

    /**
     * Lays one craft into the open grid from {@link #assign}'s choice of slots, ledger-admitted stacks
     * included, after returning whatever the grid held. With a usable crafting station open (its own grid,
     * the AE2 Crafting Terminal) the assignment follows {@link #craftOnce}'s inventory-first rule: the
     * inventory alone, and only when that falls short the inventory plus the station, whose share is drawn
     * all-or-nothing before anything is placed ({@link #drawFromStation}; a short draw has been given back
     * and nothing was placed).
     *
     * <p>Puts everything back and reports FALLBACK unless the result slot then shows {@code expected}. The
     * station's share goes back first ({@link #giveBack}: station, else inventory, else feet); the rest goes
     * to the main inventory, all of it or none ({@link #returnGridToInventory} semantics), so inventory items
     * that do not fit stay in the grid — visible, never voided or duplicated.
     *
     * <p>{@code layout} is the plan's per-slot item choice for this craft (null: none); see
     * {@link #tryFillFinalGrid}. {@code allowProtected} is the job's protected flag: its assignment may
     * pick protected stacks, in {@link #assign}'s tier order as always.
     */
    private static FillOutcome placeFromLedger(ServerPlayerEntity player, Container menu, CraftingGrids.Grid grid,
                                               List<Ingredient> cells, ItemStack expected,
                                               ProducedLedger<Item, LedgerVariant> ledger, List<Item> layout,
                                               boolean allowProtected) {
        if (!returnGridToInventory(player, menu, grid)) {
            return FillOutcome.fallback();
        }
        // One Slot.set per cell: it is what makes the menu recompute its result (a write to a wrapped
        // inventory underneath would leave the result slot stale).
        List<Slot> inputs = grid.inputs();
        PlayerInventory inventory = player.inventory;
        // Phase 1: match every cell to an inventory slot without mutating; an open station joins the pool
        // only for what the inventory lacks, exactly as craftOnce assigns a non-station-planned job (and,
        // as there, a protected job with a station open uses the pooled pass straight away, #40).
        StationStorage station = StationStorages.open(player);
        Assignment assignment = allowProtected && station != null
                ? assign(inventory, cells, ledger, station, layout, true)
                : assign(inventory, cells, ledger, null, layout, allowProtected);
        if (assignment.missing != null && station != null && !allowProtected) {
            assignment = assign(inventory, cells, ledger, station, layout, allowProtected);
        }
        if (assignment.missing != null) {
            return FillOutcome.fallback();
        }
        // Phase 2a: the station's share first, all or nothing. A short draw was given back (station, else
        // inventory, else feet) and nothing was placed, so a plain fallback loses nothing.
        Map<Item, Integer> stationTakes = assignment.stationTakes;
        if (!stationTakes.isEmpty() && drawFromStation(player, station, stationTakes) != null) {
            return FillOutcome.fallback();
        }
        // Phase 2b: place — every cell exactly one item. The inventory's stacks are shrunk as always; the
        // station's share (drawn above, held by nothing since) goes straight into its cells.
        int[] slots = assignment.slots;
        Item[] stationItems = assignment.stationItems;
        int cell = 0;
        ItemStack inventoryItemInFlight = ItemStack.EMPTY; // shrunk from the inventory, not yet in its cell
        try {
            for (; cell < slots.length; cell++) {
                ItemStack one;
                if (slots[cell] == STATION_CELL) {
                    one = new ItemStack(stationItems[cell]);
                } else if (slots[cell] >= 0) {
                    ItemStack stack = inventory.getItem(slots[cell]);
                    one = copyWithCount(stack, 1);
                    stack.shrink(1);
                    if (stack.isEmpty()) {
                        inventory.setItem(slots[cell], ItemStack.EMPTY);
                    }
                    inventoryItemInFlight = one;
                } else {
                    continue; // a blank cell
                }
                inputs.get(cell).set(one); // can run mod recipe code (the menu recomputes its result)
                inventoryItemInFlight = ItemStack.EMPTY;
            }
        } catch (Throwable t) {
            // The drawn share is real. Cells before the failing one hold their items (visible in the grid);
            // the station's share for every later cell is still in hand, and the failing cell's too unless its
            // write landed before the throw (a throwing result recompute runs after the write; the grid was
            // empty before placing), so only an empty cell's item goes back (station, else inventory, else
            // feet). One failed give-back never stops the rest.
            for (int rest = cell; rest < slots.length; rest++) {
                if (slots[rest] == STATION_CELL && (rest > cell || inputs.get(rest).getItem().isEmpty())) {
                    try {
                        giveBack(player, station, stationItems[rest], 1);
                    } catch (Throwable giveBackFailure) {
                        t.addSuppressed(giveBackFailure);
                    }
                }
            }
            // The failing cell's item, if it came from the inventory (already shrunk) and never landed, goes
            // back to the inventory (else the feet); later inventory cells were not touched yet.
            if (!inventoryItemInFlight.isEmpty() && cell < slots.length && inputs.get(cell).getItem().isEmpty()) {
                try {
                    insertIntoMainInventory(player, inventoryItemInFlight, null);
                } catch (Throwable giveBackFailure) {
                    t.addSuppressed(giveBackFailure);
                }
            }
            throw t;
        }
        inventory.setChanged();
        ItemStack result = grid.result().getItem();
        if (result.isEmpty() || !ItemStack.isSame(result, expected)) {
            // The station's share goes back where it came from first (station, else inventory, else feet), so
            // it never strands in the grid where the fallback auto-craft could not see it. Each cell is emptied
            // before its item goes back, so a cell that will not clear keeps its item (visible) and gets no
            // second copy. A clear can run mod recipe code and throw after the write landed: the emptiness
            // check still runs (finally), and one cell's failure never stops the others; the first failure is
            // rethrown once the grid is back in a consistent state.
            Throwable failure = null;
            for (int c = 0; c < slots.length; c++) {
                if (slots[c] != STATION_CELL) {
                    continue;
                }
                try {
                    inputs.get(c).set(ItemStack.EMPTY);
                } catch (Throwable clearFailure) {
                    failure = suppress(failure, clearFailure);
                } finally {
                    if (inputs.get(c).getItem().isEmpty()) {
                        try {
                            giveBack(player, station, stationItems[c], 1);
                        } catch (Throwable giveBackFailure) {
                            failure = suppress(failure, giveBackFailure);
                        }
                    }
                }
            }
            try {
                returnGridToInventory(player, menu, grid);
                syncWholeMenu(player, menu);
            } catch (Throwable restoreFailure) {
                failure = suppress(failure, restoreFailure);
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            if (failure != null) {
                throw new IllegalStateException("Crafting grid rollback failed", failure);
            }
            return FillOutcome.fallback();
        }
        assignment.chargeTo(ledger);
        syncWholeMenu(player, menu);
        return new FillOutcome(FillOutcome.Kind.FILLED, result.copy());
    }

    /** The first failure, with every later one attached to it as suppressed. */
    private static Throwable suppress(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * The recipe's ingredient for each cell of a {@code width}×{@code height} grid, row-major, null for
     * an empty cell: a shaped recipe anchored top-left, anything else in order. Null when the recipe
     * does not fit. A modded shape that is not a {@link ShapedRecipe} may land wrong;
     * {@link #placeFromLedger} catches that by checking the result.
     */
    private static List<Ingredient> gridLayout(IRecipe<?> recipe, int width, int height) {
        if (width < 2 || height < 2) {
            return null;
        }
        List<Ingredient> cells = new ArrayList<>();
        for (int i = 0; i < width * height; i++) {
            cells.add(null);
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) recipe;
            int recipeWidth = shaped.getWidth();
            if (recipeWidth <= 0 || recipeWidth > width || shaped.getHeight() > height
                    || ingredients.size() > recipeWidth * shaped.getHeight()) {
                return null;
            }
            for (int i = 0; i < ingredients.size(); i++) {
                Ingredient ingredient = ingredients.get(i);
                if (!ingredient.isEmpty()) {
                    cells.set((i / recipeWidth) * width + i % recipeWidth, ingredient);
                }
            }
            return cells;
        }
        int cell = 0;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            if (cell >= cells.size()) {
                return null;
            }
            cells.set(cell++, ingredient);
        }
        return cells;
    }

    /**
     * Returns the player's open crafting grid contents to the main inventory, matching vanilla's recipe
     * book (clicking a recipe clears the grid, or no-ops if it wouldn't all fit). Also a correctness fix:
     * the planner/executor only read the 36 main slots, so items sitting in the grid would be invisible.
     * Covers any grid {@link CraftingGrids} knows, a usable AE2 Crafting Terminal's included.
     */
    public static void clearOpenCraftingGrid(ServerPlayerEntity player) {
        if (player.isSpectator()) {
            return;
        }
        Container menu = player.containerMenu;
        CraftingGrids.Grid grid = CraftingGrids.find(menu);
        if (grid != null) {
            returnGridToInventory(player, menu, grid);
        }
    }

    /**
     * Moves the grid's contents into the main inventory, or leaves them all in place when they would not
     * all fit (vanilla {@code testClearGrid} semantics: never drops items to the world here).
     *
     * @return true when the grid is empty afterwards
     */
    private static boolean returnGridToInventory(ServerPlayerEntity player, Container menu, CraftingGrids.Grid grid) {
        List<ItemStack> contents = new ArrayList<>();
        List<Slot> filled = new ArrayList<>();
        for (Slot slot : grid.inputs()) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
                filled.add(slot);
            }
        }
        if (contents.isEmpty()) {
            return true;
        }
        if (!fitsInMainInventory(player.inventory, contents)) {
            return false;
        }
        for (Slot slot : filled) {
            slot.set(ItemStack.EMPTY);
        }
        for (ItemStack stack : contents) {
            insertIntoMainInventory(player, stack, null);
        }
        menu.broadcastChanges();
        return true;
    }

    private static boolean sameItemSameTag(ItemStack a, ItemStack b) {
        return ItemStack.isSame(a, b) && ItemStack.tagMatches(a, b);
    }

    private static ItemStack copyWithCount(ItemStack stack, int count) {
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }

    /** Non-mutating check that every stack would fit into the 36 main inventory slots together. */
    private static boolean fitsInMainInventory(PlayerInventory inventory, List<ItemStack> stacks) {
        ItemStack[] snapshot = new ItemStack[MAIN_SLOTS];
        for (int i = 0; i < MAIN_SLOTS; i++) {
            snapshot[i] = inventory.getItem(i).copy();
        }
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            for (int i = 0; i < snapshot.length && !remaining.isEmpty(); i++) {
                ItemStack existing = snapshot[i];
                if (!existing.isEmpty() && sameItemSameTag(existing, remaining)) {
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
                    snapshot[i] = copyWithCount(remaining, moved);
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
     * Vanilla recipe-book parity: a plain stack (not damaged, enchanted, or renamed). Enchanted or
     * renamed stacks are still consumed by a job the player confirmed for them (issue #40; see
     * {@link #usableAsIngredient(ItemStack, boolean)}); damaged stacks never are, beyond the ledger.
     */
    public static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.hasCustomHoverName();
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
        return !stack.isEmpty() && !stack.isDamaged() && (stack.isEnchanted() || stack.hasCustomHoverName());
    }

    /** A cell {@link #assign} fills from the crafting station's storage, not an inventory slot. */
    private static final int STATION_CELL = -2;

    /**
     * One inventory slot per ingredient ({@code -1} for an empty cell), chosen without mutating anything,
     * or the first ingredient nothing could satisfy. A stack {@link #usableAsIngredient} rejects is chosen
     * only when no acceptable stack matches and {@code ledger} (null: never) still admits its exact variant
     * through {@link LedgerTally#admits}: only rejected copies above those the player held before the job
     * produced anything, per variant. Every pick is recorded in the tally, which {@link Assignment#chargeTo}
     * charges: rejected picks, plus plain picks beyond the job's rejected copies of that item the player
     * still holds.
     *
     * <p>With an open crafting station's storage ({@code station}, null for none) in the pool,
     * {@link #preferredItems} picks each cell's item from inventory plus station stock, following the
     * plan's layout when it still fits that pool and falling back to {@link SlotAssignment} otherwise.
     * Within that item the player's own stacks go first, so a craft only reaches
     * into the station for what the inventory lacks:
     * <ol>
     *   <li>the preferred item from the inventory;</li>
     *   <li>the preferred item from the station (rather than an inventory alternative: taking a different
     *       item than the plan chose could starve a later step the plan counted on);</li>
     *   <li>(with a ledger, #26 pass only) a refused inventory copy of the preferred item the tally
     *       admits: the job's altered intermediate, which the plan's counts include;</li>
     *   <li>(with {@code allowProtected} only, #40) a protected inventory copy of the preferred item:
     *       still before any alternative item, which a later cell may need alone;</li>
     *   <li>any usable inventory match;</li>
     *   <li>any of the ingredient's items from the station;</li>
     *   <li>(with {@code allowProtected} only) any protected inventory stack the ingredient accepts;</li>
     *   <li>(with a ledger) a refused inventory stack the tally admits, the last resort as before.</li>
     * </ol>
     * Station cells are marked {@link #STATION_CELL} and summed per item in {@link Assignment#stationTakes};
     * they count as plain picks in the tally (a station only holds plain items), which can only draw the
     * ledger down, never admit more. A protected pick counts as plain in the tally too: the plan counted
     * the stack like material, and charging it against the job's produced count would refuse the altered
     * intermediates the job really did make.
     *
     * <p>{@code layout} is the plan's per-slot item choice for this craft, one entry per non-empty
     * cell in cell order (null or a mismatch: decide with {@link SlotAssignment}); see
     * {@link #preferredItems}.
     */
    private static Assignment assignPass(PlayerInventory inventory, List<Ingredient> ingredients,
                                         ProducedLedger<Item, LedgerVariant> ledger, StationStorage station,
                                         List<Item> layout, boolean admittedPreference,
                                         boolean allowProtected) {
        int[] slots = new int[ingredients.size()];
        Item[] stationItems = new Item[ingredients.size()];
        int[] reserved = new int[MAIN_SLOTS];
        LedgerTally<Item, LedgerVariant> tally = new LedgerTally<Item, LedgerVariant>();
        if (ledger != null) {
            countRefused(inventory, tally, allowProtected);
            tally.open(ledger);
        }
        StationPool pool = station == null ? null : new StationPool(station);
        Map<Item, Integer> taken = pool == null ? Collections.<Item, Integer>emptyMap() : pool.taken;
        Map<Item, Integer> stock = new HashMap<>();
        Item[] preferred = preferredItems(inventory, ingredients, pool, stock, layout,
                ledger != null && admittedPreference ? tally : null, allowProtected);
        // Whether every non-blank cell took its preferred item straight from an inventory stack or the
        // station pool (no fallback): only then is one assignment provably k crafts' worth of picks.
        boolean preferredFilled = true;
        for (int n = 0; n < slots.length; n++) {
            Ingredient ingredient = ingredients.get(n);
            if (ingredient == null || ingredient.isEmpty()) {
                slots[n] = -1;
                continue;
            }
            int slot;
            Item fromStation = null;
            if (pool == null) {
                if (preferred[n] != null) {
                    slot = findPreferredSlot(inventory, ingredient, preferred[n], reserved);
                } else {
                    slot = -1;
                }
                if (slot < 0) {
                    // Everything findMatchingSlot tries past the preferred item (a ledger-admitted
                    // altered copy of it, any usable stack, a protected stack, a refused stack the
                    // tally admits) depends on more than the shared slot rule; they rule out batching.
                    preferredFilled = false;
                    slot = findMatchingSlot(inventory, ingredient, preferred[n], reserved,
                            ledger == null ? null : tally, admittedPreference, allowProtected);
                }
            } else {
                slot = preferred[n] == null ? -1 : findPreferredSlot(inventory, ingredient, preferred[n], reserved);
                if (slot < 0) {
                    fromStation = pool.take(ingredient,
                            preferred[n] == null ? null : Collections.singletonList(preferred[n]));
                }
                if (slot < 0 && fromStation == null && preferred[n] != null && ledger != null
                        && admittedPreference) {
                    // The station's plain copy of the preferred item came first; the job's altered
                    // copy of it still beats an alternative item, which a later cell may need alone.
                    slot = findRefusedPreferredSlot(inventory, ingredient, preferred[n], reserved, tally,
                            allowProtected);
                }
                if (slot < 0 && fromStation == null && preferred[n] != null && allowProtected) {
                    // The plan's own item as a protected copy (#40), after its plain copies in the
                    // inventory and the station, but before any alternative item: a later cell may
                    // need that alternative alone. A fallback pick: it rules out batching.
                    slot = findProtectedSlot(inventory, ingredient, preferred[n], reserved);
                    if (slot >= 0) {
                        preferredFilled = false;
                    }
                }
                if (slot < 0 && fromStation == null) {
                    // Fallbacks below (any usable stack, any station alternative, a protected stack,
                    // a ledger-admitted refused stack) depend on more than the shared slot rule; they
                    // rule out batching.
                    preferredFilled = false;
                    slot = findUsableSlot(inventory, ingredient, reserved);
                }
                if (slot < 0 && fromStation == null) {
                    fromStation = pool.take(ingredient, CraftPlanner.slotAlternatives(ingredient));
                }
                if (slot < 0 && fromStation == null && allowProtected) {
                    // The protected tier (#40): the player's confirmed special copies, taken only
                    // after every plain stack and the station's stock could not fill the cell. The
                    // preferred item first — an alternative could starve a later cell that needs it
                    // alone. Either pick is a fallback one (preferredFilled is already false).
                    if (preferred[n] != null) {
                        slot = findProtectedSlot(inventory, ingredient, preferred[n], reserved);
                    }
                    if (slot < 0) {
                        slot = findProtectedSlot(inventory, ingredient, null, reserved);
                    }
                }
                if (slot < 0 && fromStation == null && ledger != null) {
                    slot = findRefusedSlot(inventory, ingredient, reserved, tally, allowProtected);
                }
            }
            if (fromStation != null) {
                slots[n] = STATION_CELL;
                stationItems[n] = fromStation;
                if (ledger != null) {
                    tally.picked(fromStation, null, false);
                }
                continue;
            }
            if (slot < 0) {
                return new Assignment(slots, tally, ingredient, taken, stationItems, preferred, stock,
                        pool != null, preferredFilled);
            }
            reserved[slot]++;
            slots[n] = slot;
            if (ledger != null) {
                ItemStack stack = inventory.getItem(slot);
                // A protected pick counts as plain in the ledger: the plan counted the stack as
                // material, and countRefused keeps protected stacks out of the refused baseline, so
                // recording it refused would wrongly spend the job's altered-copy headroom.
                boolean refused = !usableAsIngredient(stack, allowProtected);
                tally.picked(stack.getItem(), refused ? variantOf(stack) : null, refused);
            }
        }
        return new Assignment(slots, tally, null, taken, stationItems, preferred, stock,
                pool != null, preferredFilled);
    }

    /**
     * {@link #assignPass} with the job's admitted altered copies counted in the preference rule and
     * the plan's layout check (#26); when that leaves a cell unfilled, the pre-#26 rule exactly. The
     * rule cannot see which cells accept an altered copy, so counting one can steer a cell to an item
     * only another cell could take; the second pass makes the change never worse than before it. A
     * pass changes no pool state (the ledger's preexisting snapshot is rewritten with equal values).
     * {@code allowProtected} adds the protected tier of passes' pick order (see {@link #assignPass}).
     */
    private static Assignment assign(PlayerInventory inventory, List<Ingredient> ingredients,
                                     ProducedLedger<Item, LedgerVariant> ledger, StationStorage station,
                                     List<Item> layout, boolean allowProtected) {
        Assignment first = assignPass(inventory, ingredients, ledger, station, layout, true, allowProtected);
        if (first.missing == null || ledger == null) {
            return first;
        }
        return assignPass(inventory, ingredients, ledger, station, layout, false, allowProtected);
    }

    /**
     * The station's side of one {@link #assign} call: simulated availability per item, queried lazily and at
     * most once per item (a simulated extraction is a storage lookup, not free), and what this craft has
     * claimed so far.
     */
    private static final class StationPool {
        private final StationStorage station;
        private final Map<Item, Long> available = new HashMap<>();
        /** Items claimed by this craft's cells, in claim order. */
        final Map<Item, Integer> taken = new LinkedHashMap<>();

        StationPool(StationStorage station) {
            this.station = station;
        }

        long count(Item item) {
            Long known = available.get(item);
            if (known == null) {
                known = station.available(item, Integer.MAX_VALUE);
                available.put(item, known);
            }
            return known;
        }

        /** Claims one of the first {@code candidates} item the station still has and the ingredient accepts. */
        Item take(Ingredient ingredient, List<Item> candidates) {
            if (candidates == null) {
                return null;
            }
            for (Item item : candidates) {
                Integer claimed = taken.get(item);
                if (count(item) - (claimed == null ? 0 : claimed) > 0 && ingredient.test(new ItemStack(item))) {
                    taken.merge(item, 1, Integer::sum);
                    return item;
                }
            }
            return null;
        }
    }

    /**
     * Records in {@code tally} every stack among the player's holdings that {@link #usableAsIngredient}
     * rejects, per variant: the whole player inventory (36 main slots — the pool — plus armour and
     * offhand), the carried stack of the open menu, and the open grid's input cells. Rejected stacks
     * are only ever picked from the main slots (an open station supplies plain items only, so its
     * storage is not counted: a refused copy pulled out of it mid-job is an arrival, bounded by the
     * produced count); the rest are counted for the player's-own baseline alone, so moving a stack
     * between them cannot make it look like one of the job's copies. A protected-planned job
     * ({@code allowProtected}) does not count its protected stacks here: to it they are plain material
     * (picked without admission, charged like plain picks), and a baseline entry would let the ledger
     * refuse or double-charge them; damaged stacks stay refused for every job.
     */
    private static void countRefused(PlayerInventory inventory, LedgerTally<Item, LedgerVariant> tally,
                                     boolean allowProtected) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !usableAsIngredient(stack, allowProtected)) {
                tally.held(stack.getItem(), variantOf(stack), stack.getCount());
            }
        }
        ItemStack carried = inventory.getCarried();
        if (!carried.isEmpty() && !usableAsIngredient(carried, allowProtected)) {
            tally.held(carried.getItem(), variantOf(carried), carried.getCount());
        }
        CraftingGrids.Grid grid = CraftingGrids.find(inventory.player.containerMenu);
        if (grid != null) {
            for (Slot slot : grid.inputs()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && !usableAsIngredient(stack, allowProtected)) {
                    tally.held(stack.getItem(), variantOf(stack), stack.getCount());
                }
            }
        }
    }

    /**
     * {@code stationTakes}: plain items per kind the station supplies to this craft (cells marked
     * {@link #STATION_CELL}), empty without a station. {@code stationItems}: the item each of those cells gets
     * (null elsewhere) — {@link #craftOnce} consumes by kind, but the grid hand-off places the station's share
     * into its cells one by one. {@code preferred}/{@code stock}: the per-cell items
     * {@link #preferredItems} chose and the exact counts it chose them over (kept for
     * {@link SlotAssignment#stableRepeats} at {@link #craftBatch}); {@code stationPooled}: whether the
     * station's stock joined those counts; {@code preferredFilled}: whether every non-blank cell took
     * its preferred item outright, the precondition for a batch.
     */
    private static final class Assignment {
        final int[] slots;
        final LedgerTally<Item, LedgerVariant> tally;
        final Ingredient missing;
        final Map<Item, Integer> stationTakes;
        final Item[] stationItems;
        final Item[] preferred;
        final Map<Item, Integer> stock;
        final boolean stationPooled;
        final boolean preferredFilled;

        Assignment(int[] slots, LedgerTally<Item, LedgerVariant> tally, Ingredient missing,
                   Map<Item, Integer> stationTakes, Item[] stationItems, Item[] preferred,
                   Map<Item, Integer> stock, boolean stationPooled, boolean preferredFilled) {
            this.slots = slots;
            this.tally = tally;
            this.missing = missing;
            this.stationTakes = stationTakes;
            this.stationItems = stationItems;
            this.preferred = preferred;
            this.stock = stock;
            this.stationPooled = stationPooled;
            this.preferredFilled = preferredFilled;
        }

        void chargeTo(ProducedLedger<Item, LedgerVariant> ledger) {
            tally.chargeTo(ledger);
        }
    }

    /**
     * The ledger's variant of a stack — item, damage, and NBT — as a count-1 copy: what the per-variant
     * baseline ({@link ProducedLedger#preexisting}) and per-variant admission ({@link LedgerTally#admits})
     * are keyed by. {@link #hashCode} is the item's identity hash alone (collisions only within one item,
     * which {@link #equals} separates); {@link #equals} is the tree's exact stack equality.
     */
    static final class LedgerVariant {
        private final ItemStack stack;

        LedgerVariant(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LedgerVariant && sameItemSameTag(stack, ((LedgerVariant) other).stack);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(stack.getItem());
        }
    }

    /** The ledger's variant of one copy of {@code stack}; its count is not part of the variant. */
    private static LedgerVariant variantOf(ItemStack stack) {
        return new LedgerVariant(copyWithCount(stack, 1));
    }

    /**
     * Per ingredient, the item the plan's layout fixes for it when that choice still fits the live pool,
     * else the item {@link SlotAssignment} picks from it (null for a blank cell or when nothing usable
     * fits). The layout is honored only when every one of its items is still an alternative of its slot
     * and the pooled stock — the usable stacks of the 36 main slots plus an open station's storage,
     * exactly what the plan counted — covers this craft's use of each; the player may have moved items
     * since, and a stale layout must not override what the pool can actually supply.
     * {@link SlotAssignment} runs over the same slot lists ({@link CraftPlanner#slotAlternatives}) and
     * the same counts, so its fallback still takes what a plan made without layouts counted on.
     * With {@code admitting} (the #26 pass of {@link #assign}) the job's admitted altered copies
     * join that stock too ({@link #countAdmitted}), since the plan counted them as well, and with
     * {@code allowProtected} so do the protected stacks of a job planned with them (issue #40): the
     * plan's counts included them, and a layout chosen or honoured over less would not fit.
     * {@code stock} receives those exact pooled counts, so a later
     * {@link SlotAssignment#stableRepeats} reads the same view the choice was made over.
     */
    private static Item[] preferredItems(PlayerInventory inventory, List<Ingredient> ingredients, StationPool pool,
                                         Map<Item, Integer> stock, List<Item> layout,
                                         LedgerTally<Item, LedgerVariant> admitting, boolean allowProtected) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && usableAsIngredient(stack, allowProtected)) {
                stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        if (admitting != null) {
            countAdmitted(ingredients, inventory, admitting, stock, allowProtected);
        }
        List<List<Item>> cells = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            cells.add(ingredient == null || ingredient.isEmpty() ? null : CraftPlanner.slotAlternatives(ingredient));
        }
        if (pool != null) {
            Set<Item> candidates = new HashSet<>();
            for (List<Item> cell : cells) {
                if (cell != null) {
                    candidates.addAll(cell);
                }
            }
            for (Item item : candidates) {
                long inStation = pool.count(item);
                if (inStation > 0) {
                    stock.merge(item, (int) Math.min(inStation, Integer.MAX_VALUE),
                            (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
                }
            }
        }
        // The layout is indexed by non-empty cell, as the solver's slots are.
        List<List<Item>> slots = new ArrayList<>();
        for (List<Item> cell : cells) {
            if (cell != null) {
                slots.add(cell);
            }
        }
        if (layout != null && layout.size() == slots.size() && layoutFits(layout, slots, stock)) {
            Item[] preferred = new Item[cells.size()];
            int s = 0;
            for (int n = 0; n < cells.size(); n++) {
                if (cells.get(n) != null) {
                    preferred[n] = layout.get(s++);
                }
            }
            return preferred;
        }
        return SlotAssignment.assignCells(cells, stock).toArray(new Item[0]);
    }

    /**
     * Whether the plan's layout can still be laid on the live pool: every item remains an alternative
     * of its slot (the i-th entry belongs to the i-th non-empty cell, the order {@code slots} is built
     * in), and the pool holds at least as many of each item as this one craft uses of it.
     */
    private static boolean layoutFits(List<Item> layout, List<List<Item>> slots, Map<Item, Integer> stock) {
        Map<Item, Integer> uses = new HashMap<>();
        for (int i = 0; i < layout.size(); i++) {
            Item item = layout.get(i);
            if (item == null || !slots.get(i).contains(item)) { // null never satisfies a slot
                return false;
            }
            uses.merge(item, 1, Integer::sum);
        }
        for (Map.Entry<Item, Integer> use : uses.entrySet()) {
            if (stock.getOrDefault(use.getKey(), 0) < use.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adds to {@code stock} the refused stacks of the main slots that {@code tally} would admit:
     * the job's altered intermediates, which the plan's counts include (the solver credits every item a
     * step produces, altered or not), so {@link #preferredItems} and the plan's layout check must see
     * them too (#26). Each stack adds at most what {@link LedgerTally#variantHeadroom} and
     * {@link LedgerTally#identityHeadroom} still allow, drawn down as stacks are counted. A copy no
     * cell of this recipe accepts is skipped: it would only inflate the count the rule spreads over
     * the cells. A protected-planned job's protected stacks never come here: they are already plain
     * stock ({@link #preferredItems}), not ledger-admitted ones.
     */
    private static void countAdmitted(List<Ingredient> ingredients, PlayerInventory inventory, LedgerTally<Item, LedgerVariant> tally,
                                      Map<Item, Integer> stock, boolean allowProtected) {
        Map<LedgerVariant, Long> variantLeft = new HashMap<>();
        Map<Item, Long> identityLeft = new HashMap<>();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || usableAsIngredient(stack, allowProtected) || !acceptedByAny(ingredients, stack)) {
                continue;
            }
            LedgerVariant variant = variantOf(stack);
            Item item = stack.getItem();
            long forVariant = variantLeft.computeIfAbsent(variant, tally::variantHeadroom);
            long forIdentity = identityLeft.computeIfAbsent(item, tally::identityHeadroom);
            int add = (int) Math.min(stack.getCount(), Math.min(forVariant, forIdentity));
            if (add > 0) {
                stock.merge(stack.getItem(), add, (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
                variantLeft.put(variant, forVariant - add);
                identityLeft.put(item, forIdentity - add);
            }
        }
    }

    /** Whether any non-empty ingredient of the recipe accepts {@code stack}. */
    private static boolean acceptedByAny(List<Ingredient> ingredients, ItemStack stack) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient != null && !ingredient.isEmpty() && ingredient.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first unreserved refused stack of {@code preferred} of a variant the tally still admits that
     * the ingredient accepts, or -1: the job's altered copy of the item the rule chose, taken before
     * any alternative item a later cell may need alone (#26). Protected stacks are not ledger material
     * for a job planned with them (they have their own, earlier tier), so they never match here.
     */
    private static int findRefusedPreferredSlot(PlayerInventory inventory, Ingredient ingredient, Item preferred,
                                                int[] reserved, LedgerTally<Item, LedgerVariant> tally,
                                                boolean allowProtected) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == preferred && stack.getCount() - reserved[i] > 0
                    && !usableAsIngredient(stack, allowProtected)
                    && tally.admits(stack.getItem(), variantOf(stack))
                    && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A plain stack of {@code preferred} ({@link #preferredItems}) when one matches, then (a protected-planned
     * job only) a protected copy of it; otherwise the first plain match in inventory order, then (a
     * protected-planned job only) any protected stack, and last (with a ledger) a refused stack the tally
     * admits: the inventory tiers of {@link #assignPass}'s documented order.
     */
    private static int findMatchingSlot(PlayerInventory inventory, Ingredient ingredient, Item preferred,
                                        int[] reserved, LedgerTally<Item, LedgerVariant> tally,
                                        boolean admittedPreference, boolean allowProtected) {
        if (preferred != null) {
            int slot = findPreferredSlot(inventory, ingredient, preferred, reserved);
            if (slot < 0 && tally != null && admittedPreference) {
                slot = findRefusedPreferredSlot(inventory, ingredient, preferred, reserved, tally,
                        allowProtected);
            }
            if (slot < 0 && allowProtected) {
                // The plan's own item before any plain alternative (#40): the plan may have given a
                // plain alternative to a later cell that accepts nothing else, and taking it here
                // would starve that cell. Plain copies of the preferred item were tried first.
                slot = findProtectedSlot(inventory, ingredient, preferred, reserved);
            }
            if (slot >= 0) {
                return slot;
            }
        }
        int slot = findUsableSlot(inventory, ingredient, reserved);
        if (slot >= 0 || tally == null) {
            return slot;
        }
        if (allowProtected) {
            if (preferred != null) {
                slot = findProtectedSlot(inventory, ingredient, preferred, reserved);
                if (slot >= 0) {
                    return slot;
                }
            }
            slot = findProtectedSlot(inventory, ingredient, null, reserved);
            if (slot >= 0) {
                return slot;
            }
        }
        return findRefusedSlot(inventory, ingredient, reserved, tally, allowProtected);
    }

    /**
     * The first unreserved protected stack ({@link #isProtected}) the ingredient accepts — of
     * {@code preferred} when non-null, else of any item — or -1. The #40 tier, reached only by a job
     * planned with protected stacks and only after every plain stack and the station's stock fell
     * short, so a plain copy of the needed item always wins.
     */
    private static int findProtectedSlot(PlayerInventory inventory, Ingredient ingredient, Item preferred,
                                         int[] reserved) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && isProtected(stack)
                    && (preferred == null || stack.getItem() == preferred) && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /** The first unreserved usable stack of {@code preferred} the ingredient accepts, or -1. */
    private static int findPreferredSlot(PlayerInventory inventory, Ingredient ingredient, Item preferred,
                                         int[] reserved) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == preferred && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /** The first unreserved usable stack the ingredient accepts, in inventory order, or -1. */
    private static int findUsableSlot(PlayerInventory inventory, Ingredient ingredient, int[] reserved) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The first unreserved refused stack of a variant the tally still admits that the ingredient
     * accepts, or -1. As in {@link #findRefusedPreferredSlot}, protected stacks never match for a job
     * planned with them: damaged copies are the ledger's alone there.
     */
    private static int findRefusedSlot(PlayerInventory inventory, Ingredient ingredient, int[] reserved,
                                       LedgerTally<Item, LedgerVariant> tally, boolean allowProtected) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && !usableAsIngredient(stack, allowProtected)
                    && tally.admits(stack.getItem(), variantOf(stack)) && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Inserts into the 36 main slots only (merge into matching stacks, then empty slots); overflow goes into
     * {@code station} (an open crafting station's storage, null for none) before whatever it refuses drops at
     * the player's feet. Deliberately not {@code PlayerInventory.add}: that can stash items into the offhand
     * slot, where later steps of the job would not find them.
     *
     * @return whether the station took any of it
     */
    private static boolean insertIntoMainInventory(ServerPlayerEntity player, ItemStack stack, StationStorage station) {
        PlayerInventory inventory = player.inventory;
        for (int i = 0; i < MAIN_SLOTS && !stack.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!existing.isEmpty() && sameItemSameTag(existing, stack)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                }
            }
        }
        for (int i = 0; i < MAIN_SLOTS && !stack.isEmpty(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, copyWithCount(stack, Math.min(stack.getCount(), stack.getMaxStackSize())));
                stack.shrink(inventory.getItem(i).getCount());
            }
        }
        inventory.setChanged();
        boolean stored = false;
        if (!stack.isEmpty() && station != null) {
            int before = stack.getCount();
            stack = insertIntoStation(station, stack);
            stored = stack.getCount() < before;
        }
        if (!stack.isEmpty()) {
            noteDrop(stack, station, stored);
        }
        // A batch's stack can be far over the max stack size: drop it as ordinary stacks, never one
        // oversized item entity.
        while (!stack.isEmpty()) {
            ItemStack chunk = copyWithCount(stack, Math.min(stack.getCount(), Math.max(1, stack.getMaxStackSize())));
            stack.shrink(chunk.getCount());
            player.drop(chunk, false);
        }
        return stored;
    }

    /** The first stack dropped at the feet since the last {@link #takeDropped}; server thread only. */
    private static ItemStack firstDrop = ItemStack.EMPTY;

    /**
     * Records the first drop and logs it once with what the station still claims it would accept, so a
     * check-versus-insert disagreement (the #35 spill that a bulk step's room checks did not predict)
     * shows its cause in the log, along with the call path that dropped.
     */
    private static void noteDrop(ItemStack overflow, StationStorage station, boolean stationTookSome) {
        if (!firstDrop.isEmpty()) {
            return;
        }
        firstDrop = overflow.copy();
        long stillAcceptable = -1;
        if (station != null) {
            try {
                stillAcceptable = station.acceptable(overflow, overflow.getCount());
            } catch (RuntimeException | Error ignored) {
                // Diagnostics only: whatever the station's mod throws, the overflow must still drop below.
            }
        }
        Constants.LOG.warn("SprawlCrafting: dropping {} x{} at the player's feet (station: {}, took part of it: {},"
                        + " now says it would accept: {})", overflow.getItem(), overflow.getCount(),
                station == null ? "none" : station.getClass().getName(), stationTookSome, stillAcceptable,
                new Throwable("drop site"));
    }

    /**
     * The name of the first output dropped at the player's feet since the last call (then forgotten), or
     * null. A bulk step checks it after every craft call and stops on any drop, so a room check that
     * disagreed with the real insert costs one batch, never a spill repeated every tick.
     */
    static ITextComponent takeDropped() {
        if (firstDrop.isEmpty()) {
            return null;
        }
        ITextComponent name = firstDrop.getHoverName();
        firstDrop = ItemStack.EMPTY;
        return name;
    }

    private static ITextComponent describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        return options.length > 0 ? options[0].getHoverName() : new StringTextComponent("?");
    }
}
