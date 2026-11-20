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
 * says it produced itself (some mods enchant an item on its first inventory tick, after insertion). The
 * ledger's player-own baseline is a read-only exception to the first: it counts the filter-rejected
 * stacks the player holds outside the main slots too (see {@link #countRefused}), never picked from.
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    private static final int MAIN_SLOTS = CraftPlanner.MAIN_SLOTS;

    /** Outcome of one craft. Java 8 stand-in for the modern sealed interface. */
    public static final class CraftResult {
        public enum Kind { SUCCESS, MISSING_INGREDIENT, RECIPE_GONE }

        private final Kind kind;
        private final ItemStack crafted;
        private final ITextComponent missing;
        private final boolean usedStation;

        private CraftResult(Kind kind, ItemStack crafted, ITextComponent missing, boolean usedStation) {
            this.kind = kind;
            this.crafted = crafted;
            this.missing = missing;
            this.usedStation = usedStation;
        }

        static CraftResult missing(ITextComponent missing) {
            return new CraftResult(Kind.MISSING_INGREDIENT, ItemStack.EMPTY, missing, false);
        }

        static CraftResult recipeGone() {
            return new CraftResult(Kind.RECIPE_GONE, ItemStack.EMPTY, null, false);
        }

        public Kind kind() {
            return kind;
        }

        /** Display copy of what was produced ({@link Kind#SUCCESS}). */
        public ItemStack crafted() {
            return crafted;
        }

        /** The vanished ingredient's name ({@link Kind#MISSING_INGREDIENT}). */
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
     * the inventory has since lost (the player moved items mid-job). {@link CraftResult#usedStation()}
     * reports that, so the job pauses rather than cancels once the station goes away.
     */
    public static CraftResult craftOnce(ServerPlayerEntity player, ResourceLocation recipeId,
                                        ProducedLedger<Item, LedgerVariant> ledger, boolean stationPlanned) {
        IRecipe<?> recipe = RecipeIds.byId(player.getServer().getRecipeManager(), recipeId).orElse(null);
        if (!(recipe instanceof ICraftingRecipe)) {
            return CraftResult.recipeGone();
        }
        ItemStack result = RecipeIds.resultOf(recipe);
        if (result.isEmpty()) {
            return CraftResult.recipeGone();
        }

        // Phase 1: match every ingredient slot to an inventory slot (or a crafting station's storage)
        // without mutating, so a shortfall discovered at slot N leaves slots 1..N-1 untouched.
        PlayerInventory inventory = player.inventory;
        List<Ingredient> ingredients = recipe.getIngredients();
        // A crafting station open right now (AE2 Crafting Terminal) adds its storage to the pool. It is
        // re-opened for every craft, so a closed or offline station stops being drawn from at once.
        final StationStorage station = StationStorages.open(player);
        Assignment assignment = null;
        if (station == null || !stationPlanned) {
            assignment = assign(inventory, ingredients, ledger, null);
            if (assignment.missing != null && station != null) {
                assignment = null; // the inventory alone fell short: let the open station supply the rest
            }
        }
        if (assignment == null) {
            assignment = assign(inventory, ingredients, ledger, station);
        }
        if (assignment.missing != null) {
            return CraftResult.missing(describe(assignment.missing));
        }

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
        ItemStack crafted = result.copy();
        storedInStation |= insertIntoMainInventory(player, result.copy(), station);
        ledger.produced(crafted.getItem(), crafted.getCount());
        return new CraftResult(CraftResult.Kind.SUCCESS, crafted, null, !stationTakes.isEmpty() || storedInStation);
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
        int maxStack = new ItemStack(item).getMaxStackSize();
        for (int left = count; left > 0; ) {
            int chunk = Math.min(left, maxStack);
            left -= chunk;
            ItemStack rest = insertIntoStation(station, new ItemStack(item, chunk));
            if (!rest.isEmpty()) {
                insertIntoMainInventory(player, rest, null);
            }
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
     * intermediate the job crafted that has since been altered (see {@link ProducedLedger}), the grid is laid
     * out here instead, from the same assignment {@link #craftOnce} would consume. The same manual layout is
     * the only path for a grid that is not a recipe-book menu (the AE2 Crafting Terminal's:
     * {@link CraftingGrids.Grid#recipeBookPlacement()} is false, there is no {@code handlePlacement} and no
     * recipe book to consult), and a grid that is not on screen is never filled.
     */
    public static FillOutcome tryFillFinalGrid(ServerPlayerEntity player, ResourceLocation recipeId,
                                               ProducedLedger<Item, LedgerVariant> ledger) {
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
        boolean needsLedger = cells != null && assign(player.inventory, cells, null, null).missing != null
                && assign(player.inventory, cells, ledger, null).missing == null;
        if (needsLedger || !grid.recipeBookPlacement()) {
            // No handlePlacement outside a recipe-book menu, so the layout is ours; a recipe that does not
            // fit the grid has nothing to lay out there.
            return cells == null ? FillOutcome.fallback() : placeFromLedger(player, menu, grid, cells, expected, ledger);
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
     */
    private static FillOutcome placeFromLedger(ServerPlayerEntity player, Container menu, CraftingGrids.Grid grid,
                                               List<Ingredient> cells, ItemStack expected,
                                               ProducedLedger<Item, LedgerVariant> ledger) {
        if (!returnGridToInventory(player, menu, grid)) {
            return FillOutcome.fallback();
        }
        // One Slot.set per cell: it is what makes the menu recompute its result (a write to a wrapped
        // inventory underneath would leave the result slot stale).
        List<Slot> inputs = grid.inputs();
        PlayerInventory inventory = player.inventory;
        // Phase 1: match every cell to an inventory slot without mutating; an open station joins the pool
        // only for what the inventory lacks, exactly as craftOnce assigns a non-station-planned job.
        StationStorage station = StationStorages.open(player);
        Assignment assignment = assign(inventory, cells, ledger, null);
        if (assignment.missing != null && station != null) {
            assignment = assign(inventory, cells, ledger, station);
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

    /** Vanilla recipe-book parity: never consume damaged, enchanted, or renamed stacks. */
    public static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.hasCustomHoverName();
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
     * {@link #preferredItems} picks each cell's item from inventory plus station stock, exactly as the plan's
     * {@link SlotAssignment} did. Within that item the player's own stacks go first, so a craft only reaches
     * into the station for what the inventory lacks:
     * <ol>
     *   <li>the preferred item from the inventory;</li>
     *   <li>the preferred item from the station (rather than an inventory alternative: taking a different
     *       item than the plan's rule chose could starve a later step the plan counted on);</li>
     *   <li>any usable inventory match;</li>
     *   <li>any of the ingredient's items from the station;</li>
     *   <li>(with a ledger) a refused inventory stack the tally admits, the last resort as before.</li>
     * </ol>
     * Station cells are marked {@link #STATION_CELL} and summed per item in {@link Assignment#stationTakes};
     * they count as plain picks in the tally (a station only holds plain items), which can only draw the
     * ledger down, never admit more.
     */
    private static Assignment assign(PlayerInventory inventory, List<Ingredient> ingredients,
                                     ProducedLedger<Item, LedgerVariant> ledger, StationStorage station) {
        int[] slots = new int[ingredients.size()];
        Item[] stationItems = new Item[ingredients.size()];
        int[] reserved = new int[MAIN_SLOTS];
        LedgerTally<Item, LedgerVariant> tally = new LedgerTally<Item, LedgerVariant>();
        if (ledger != null) {
            countRefused(inventory, tally);
            tally.open(ledger);
        }
        StationPool pool = station == null ? null : new StationPool(station);
        Map<Item, Integer> taken = pool == null ? Collections.<Item, Integer>emptyMap() : pool.taken;
        Item[] preferred = preferredItems(inventory, ingredients, pool);
        for (int n = 0; n < slots.length; n++) {
            Ingredient ingredient = ingredients.get(n);
            if (ingredient == null || ingredient.isEmpty()) {
                slots[n] = -1;
                continue;
            }
            int slot;
            Item fromStation = null;
            if (pool == null) {
                slot = findMatchingSlot(inventory, ingredient, preferred[n], reserved, ledger == null ? null : tally);
            } else {
                slot = preferred[n] == null ? -1 : findPreferredSlot(inventory, ingredient, preferred[n], reserved);
                if (slot < 0) {
                    fromStation = pool.take(ingredient,
                            preferred[n] == null ? null : Collections.singletonList(preferred[n]));
                }
                if (slot < 0 && fromStation == null) {
                    slot = findUsableSlot(inventory, ingredient, reserved);
                }
                if (slot < 0 && fromStation == null) {
                    fromStation = pool.take(ingredient, CraftPlanner.slotAlternatives(ingredient));
                }
                if (slot < 0 && fromStation == null && ledger != null) {
                    slot = findRefusedSlot(inventory, ingredient, reserved, tally);
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
                return new Assignment(slots, tally, ingredient, taken, stationItems);
            }
            reserved[slot]++;
            slots[n] = slot;
            if (ledger != null) {
                ItemStack stack = inventory.getItem(slot);
                boolean refused = !usableAsIngredient(stack);
                tally.picked(stack.getItem(), refused ? variantOf(stack) : null, refused);
            }
        }
        return new Assignment(slots, tally, null, taken, stationItems);
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
     * between them cannot make it look like one of the job's copies.
     */
    private static void countRefused(PlayerInventory inventory, LedgerTally<Item, LedgerVariant> tally) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !usableAsIngredient(stack)) {
                tally.held(stack.getItem(), variantOf(stack), stack.getCount());
            }
        }
        ItemStack carried = inventory.getCarried();
        if (!carried.isEmpty() && !usableAsIngredient(carried)) {
            tally.held(carried.getItem(), variantOf(carried), carried.getCount());
        }
        CraftingGrids.Grid grid = CraftingGrids.find(inventory.player.containerMenu);
        if (grid != null) {
            for (Slot slot : grid.inputs()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && !usableAsIngredient(stack)) {
                    tally.held(stack.getItem(), variantOf(stack), stack.getCount());
                }
            }
        }
    }

    /**
     * {@code stationTakes}: plain items per kind the station supplies to this craft (cells marked
     * {@link #STATION_CELL}), empty without a station. {@code stationItems}: the item each of those cells gets
     * (null elsewhere) — {@link #craftOnce} consumes by kind, but the grid hand-off places the station's share
     * into its cells one by one.
     */
    private static final class Assignment {
        final int[] slots;
        final LedgerTally<Item, LedgerVariant> tally;
        final Ingredient missing;
        final Map<Item, Integer> stationTakes;
        final Item[] stationItems;

        Assignment(int[] slots, LedgerTally<Item, LedgerVariant> tally, Ingredient missing,
                   Map<Item, Integer> stationTakes, Item[] stationItems) {
            this.slots = slots;
            this.tally = tally;
            this.missing = missing;
            this.stationTakes = stationTakes;
            this.stationItems = stationItems;
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
     * Per ingredient, the item {@link SlotAssignment} picks for it from the usable stacks of the 36 main
     * slots (null for a blank cell or when nothing usable fits). The solver plans every craft with the same
     * rule over the same slot lists ({@link CraftPlanner#slotAlternatives}) and counts, so each craft takes
     * what the plan counted on, not whichever stack sits first in the inventory. With a crafting station open
     * ({@code pool} non-null) its stock of every candidate item joins the counts, as it joined the plan's
     * snapshot.
     */
    private static Item[] preferredItems(PlayerInventory inventory, List<Ingredient> ingredients, StationPool pool) {
        Map<Item, Integer> stock = new HashMap<>();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && usableAsIngredient(stack)) {
                stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        List<List<Item>> slots = new ArrayList<>();
        List<Integer> cells = new ArrayList<>();
        for (int n = 0; n < ingredients.size(); n++) {
            Ingredient ingredient = ingredients.get(n);
            if (ingredient != null && !ingredient.isEmpty()) {
                slots.add(CraftPlanner.slotAlternatives(ingredient));
                cells.add(n);
            }
        }
        if (pool != null) {
            Set<Item> candidates = new HashSet<>();
            for (List<Item> slot : slots) {
                candidates.addAll(slot);
            }
            for (Item item : candidates) {
                long inStation = pool.count(item);
                if (inStation > 0) {
                    stock.merge(item, (int) Math.min(inStation, Integer.MAX_VALUE),
                            (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
                }
            }
        }
        List<Item> chosen = SlotAssignment.assign(slots, stock);
        Item[] preferred = new Item[ingredients.size()];
        for (int s = 0; s < cells.size(); s++) {
            preferred[cells.get(s)] = chosen.get(s);
        }
        return preferred;
    }

    /**
     * A stack of {@code preferred} ({@link #preferredItems}) when one matches; otherwise the first match in
     * inventory order, then (with a ledger) a refused stack the tally admits.
     */
    private static int findMatchingSlot(PlayerInventory inventory, Ingredient ingredient, Item preferred,
                                        int[] reserved, LedgerTally<Item, LedgerVariant> tally) {
        if (preferred != null) {
            int slot = findPreferredSlot(inventory, ingredient, preferred, reserved);
            if (slot >= 0) {
                return slot;
            }
        }
        int slot = findUsableSlot(inventory, ingredient, reserved);
        if (slot >= 0 || tally == null) {
            return slot;
        }
        return findRefusedSlot(inventory, ingredient, reserved, tally);
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

    /** The first unreserved refused stack of a variant the tally still admits that the ingredient accepts, or -1. */
    private static int findRefusedSlot(PlayerInventory inventory, Ingredient ingredient, int[] reserved,
                                       LedgerTally<Item, LedgerVariant> tally) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && !usableAsIngredient(stack)
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
            player.drop(stack, false);
        }
        return stored;
    }

    private static ITextComponent describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        return options.length > 0 ? options[0].getHoverName() : new StringTextComponent("?");
    }
}
