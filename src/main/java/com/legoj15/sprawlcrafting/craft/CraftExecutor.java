package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.solver.LedgerTally;
import com.legoj15.solver.ProducedLedger;
import com.legoj15.solver.SlotAssignment;

//? if >=1.20.5 {
import net.minecraft.core.component.DataComponents;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Performs a single craft of a recipe directly against a player's live inventory:
 * consumes one item per ingredient slot, returns crafting remainders (empty buckets),
 * and inserts the result as real items (overflow drops at the player's feet).
 *
 * <p>This is the per-step consumption point of DESIGN.md: ingredients are re-checked
 * against reality here, every half second — the plan only predicted them.
 *
 * <p>Two invariants shared with {@code CraftPlanner}:
 * <ul>
 *   <li>Only the 36 main inventory slots are read and written — never armor or offhand —
 *       so planning, consumption, and insertion all see the same item pool. The one addition is a
 *       crafting station the player has open ({@link StationStorages}, the AE2 Crafting Terminal),
 *       whose storage both the planner and {@link #craftOnce} add to the pool while it is open.</li>
 *   <li>Damaged, enchanted, or renamed stacks are never consumed, matching what the
 *       vanilla recipe book refuses to auto-fill ({@code StackedContents.accountSimpleStack}),
 *       except up to what the job's {@link ProducedLedger} says it produced itself: some mods
 *       enchant an item on its first inventory tick, after the job inserted it.</li>
 * </ul>
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    /** The crafting result slot is index 0 in both InventoryMenu (2×2) and CraftingMenu (3×3). */
    private static final int RESULT_SLOT = 0;

    public sealed interface CraftResult {
        /** {@code crafted} is a display copy of what was produced. */
        record Success(ItemStack crafted) implements CraftResult {}

        /** An ingredient vanished since planning; {@code missing} names it for the player. */
        record MissingIngredient(Component missing) implements CraftResult {}

        /** Recipe no longer exists or is no longer executable (datapack reload). */
        record RecipeGone() implements CraftResult {}
    }

    public sealed interface FillOutcome {
        /** The grid was filled; {@code result} is what the player will grab from the result slot. */
        record Filled(ItemStack result) implements FillOutcome {}

        /** Couldn't hand off (recipe locked, grid uncleanable, no/foreign menu) — caller auto-crafts. */
        record Fallback() implements FillOutcome {}

        /** Recipe no longer exists. */
        record RecipeGone() implements FillOutcome {}
    }

    public static CraftResult craftOnce(ServerPlayer player, ResourceLocation recipeId, ProducedLedger<Item> ledger) {
        RecipeHolder<?> holder = RecipeIds.byId(player.level().getServer().getRecipeManager(), recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) {
            return new CraftResult.RecipeGone();
        }
        ItemStack result = RecipeIds.resultOf(recipe, player.registryAccess());
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1: match every ingredient slot to an inventory slot (or a crafting station's storage)
        // without mutating, so a shortfall discovered at slot N leaves slots 1..N-1 untouched.
        Inventory inventory = player.getInventory();
        //? if >=1.21.11 {
        /*List<Ingredient> ingredients = recipe.placementInfo().ingredients();*/
        //?} else {
        List<Ingredient> ingredients = recipe.getIngredients();
        //?}
        // A crafting station open right now (AE2 Crafting Terminal) adds its storage to the pool. It is
        // re-opened for every craft, so a closed or offline station stops being drawn from at once.
        StationStorage station = StationStorages.open(player);
        Assignment assignment = assign(inventory, ingredients, ledger, station);
        if (assignment.missing() != null) {
            return new CraftResult.MissingIngredient(describe(assignment.missing()));
        }

        // Phase 2a: the station's share first, all or nothing. Its storage can change under us (another
        // player or automation drained it since the simulated check): a short extraction gives back
        // everything this craft drew and reports the item missing, before the inventory is touched.
        Map<Item, Integer> stationTakes = assignment.stationTakes();
        if (!stationTakes.isEmpty()) {
            Item shortItem = drawFromStation(player, station, stationTakes);
            if (shortItem != null) {
                return new CraftResult.MissingIngredient(new ItemStack(shortItem).getHoverName());
            }
        }

        // Phase 2b: apply — consume, return remainders, insert the result.
        List<Item> consumed = new ArrayList<>();
        for (int slot : assignment.slots()) {
            if (slot < 0) {
                continue; // a blank cell, or a cell the station supplied (consumed in phase 2a)
            }
            ItemStack stack = inventory.getItem(slot);
            consumed.add(stack.getItem());
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        stationTakes.forEach((item, count) -> {
            for (int i = 0; i < count; i++) {
                consumed.add(item);
            }
        });
        List<ItemStack> remainders = new ArrayList<>();
        for (Item item : consumed) {
            //? if >=1.21.11 {
            /*net.minecraft.world.item.ItemStackTemplate remainder = item.getCraftingRemainder();
            if (remainder != null) {
                remainders.add(remainder.create());
            }*/
            //?} else {
            if (item.hasCraftingRemainingItem()) {
                remainders.add(new ItemStack(item.getCraftingRemainingItem()));
            }
            //?}
        }
        inventory.setChanged();
        assignment.chargeTo(ledger);
        // Results and remainders go to the main inventory as always; with a station open, what would
        // otherwise drop at the player's feet goes into its storage first.
        for (ItemStack remainder : remainders) {
            insertIntoMainInventory(player, remainder, station);
        }
        ItemStack crafted = result.copy();
        insertIntoMainInventory(player, result.copy(), station);
        ledger.produced(crafted.getItem(), crafted.getCount());
        return new CraftResult.Success(crafted);
    }

    /**
     * Extracts {@code takes} from the station. Returns null when every item came out in full; otherwise
     * gives back everything this call extracted (into the station, else the inventory, else the
     * player's feet: never voided) and returns the first item that came up short.
     */
    private static Item drawFromStation(ServerPlayer player, StationStorage station, Map<Item, Integer> takes) {
        Map<Item, Integer> drawn = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> take : takes.entrySet()) {
            int got = station.extract(take.getKey(), take.getValue());
            if (got > 0) {
                drawn.merge(take.getKey(), got, Integer::sum);
            }
            if (got < take.getValue()) {
                drawn.forEach((item, count) -> giveBack(player, station, item, count));
                return take.getKey();
            }
        }
        return null;
    }

    /** Returns {@code count} plain {@code item} to the station, overflowing into the inventory, then the feet. */
    private static void giveBack(ServerPlayer player, StationStorage station, Item item, int count) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        for (int left = count; left > 0; ) {
            int chunk = Math.min(left, maxStack);
            left -= chunk;
            ItemStack rest = station.insert(new ItemStack(item, chunk));
            if (!rest.isEmpty()) {
                insertIntoMainInventory(player, rest, null);
            }
        }
    }

    /**
     * Hands the final craft off to the crafting grid the player has open, instead of
     * auto-crafting it: lays out this recipe's ingredients (already real items in the
     * inventory by this point) so vanilla computes the result for the player to grab —
     * a genuine, takeable craft rather than a faked result.
     *
     * <p>This drives vanilla's own recipe-book auto-fill ({@code handlePlacement} →
     * {@code ServerPlaceRecipe}), so it "behaves like vanilla": it relocates anything
     * already in the grid back to the inventory, lays out the recipe, and silently does
     * nothing when the grid can't be cleared into a full inventory. Like vanilla it only
     * fills recipes the player has unlocked. Whenever a clean fill isn't possible it
     * returns {@link FillOutcome.Fallback} so the caller auto-crafts and the item is never
     * lost. Success is confirmed by checking the result slot actually shows this recipe's
     * output (a no-op leaves the slot unchanged — possibly a recipe the player had laid
     * out themselves).
     *
     * <p>Vanilla's placement counts only undamaged, unenchanted, unnamed stacks, so when the
     * recipe needs an intermediate the job crafted that has since been altered (see
     * {@link ProducedLedger}), the grid is laid out here instead, from the same assignment
     * {@link #craftOnce} would consume.
     *
     * <p>The caller guarantees {@code player.containerMenu} is the open crafting grid and
     * fits the recipe (see {@code CraftQueueManager}); this re-checks the menu type defensively.
     */
    public static FillOutcome tryFillFinalGrid(ServerPlayer player, ResourceLocation recipeId,
                                               ProducedLedger<Item> ledger) {
        RecipeHolder<?> holder = RecipeIds.byId(player.level().getServer().getRecipeManager(), recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) {
            return new FillOutcome.RecipeGone();
        }
        // Vanilla parity: the recipe book only auto-fills recipes the player has unlocked.
        //? if >=1.21.11 {
        /*if (!player.getRecipeBook().contains(holder.id())) {
            return new FillOutcome.Fallback();
        }*/
        //?} else if >=1.20.2 {
        if (!player.getRecipeBook().contains(holder)) {
            return new FillOutcome.Fallback();
        }
        //?} else {
        /*if (!player.getRecipeBook().contains(holder.id())) {
            return new FillOutcome.Fallback();
        }*/
        //?}
        ItemStack expected = RecipeIds.resultOf(recipe, player.registryAccess());
        if (expected.isEmpty()) {
            return new FillOutcome.RecipeGone();
        }

        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
        int[] grid = gridSize(menu);
        if (grid == null) {
            return new FillOutcome.Fallback();
        }
        List<Ingredient> cells = gridLayout(recipe, grid[0], grid[1]);
        if (cells != null && assign(player.getInventory(), cells, null).missing() != null
                && assign(player.getInventory(), cells, ledger).missing() == null) {
            return placeFromLedger(player, menu, cells, expected, ledger);
        }
        //? if >=1.21.11 {
        /*if (!(menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu craftingMenu)) {
            return new FillOutcome.Fallback();
        }
        craftingMenu.handlePlacement(false, false, holder,
                (net.minecraft.server.level.ServerLevel) player.level(), player.getInventory());*/
        //?} else if >=1.20.2 {
        if (!(menu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> recipeBookMenu)) {
            return new FillOutcome.Fallback();
        }
        recipeBookMenu.handlePlacement(false, holder, player);
        //?} else {
        /*if (!(menu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> recipeBookMenu)) {
            return new FillOutcome.Fallback();
        }
        recipeBookMenu.handlePlacement(false, holder.value(), player); // 1.20.1 places a bare Recipe*/
        //?}

        // finishPlacingRecipe (inside handlePlacement) already recomputed + synced the result
        // slot. Confirm OUR output landed; a no-op (full inventory) or a leftover player recipe
        // leaves a different/empty result, so we fall back to auto-crafting.
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty() || !ItemStack.isSameItem(result, expected)) {
            return new FillOutcome.Fallback();
        }
        // We mutated the menu outside any client click transaction, from the end-of-tick hook
        // (after this tick's per-menu broadcastChanges already ran). handlePlacement sets the
        // grid INPUT slots silently — relying on a later broadcastChanges — and only the result
        // slot is pushed immediately. Without an explicit flush the inputs reach the client a
        // tick late (grid looks unfilled) and the deferred stateId bumps make the player's first
        // click on the result arrive stale, so the server resyncs instead of taking it (the
        // "double-click to get the item" symptom). broadcastFullState pushes one authoritative
        // ClientboundContainerSetContentPacket — all slots + carried + a fresh stateId — so the
        // grid renders complete and the first take click matches. Vanilla AbstractContainerMenu
        // method, identical on both versions; safe in the shared path.
        menu.broadcastFullState();
        return new FillOutcome.Filled(result.copy());
    }

    /**
     * Returns the player's open crafting grid contents to the main inventory, matching vanilla's
     * recipe book: clicking a recipe clears the grid back to the inventory, or no-ops if it
     * wouldn't all fit. The mod intercepts yellow (deferred) recipe clicks before vanilla's
     * {@code tryPlaceRecipe} can do this, so we replicate it when a deferred craft is requested.
     * It is also a correctness fix: the planner/executor only read the 36 main inventory slots,
     * so items sitting in the grid would otherwise be invisible to a craft started over them.
     *
     * <p>No-op for a non-crafting menu, an empty grid, or when the contents wouldn't all fit
     * (vanilla {@code testClearGrid} semantics — never drops items to the world here).
     */
    public static void clearOpenCraftingGrid(ServerPlayer player) {
        if (player.isSpectator()) {
            return;
        }
        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
        int[] grid = gridSize(menu);
        if (grid != null) {
            returnGridToInventory(player, menu, grid[0] * grid[1]);
        }
    }

    /**
     * Moves the grid's contents into the main inventory, or leaves them all in place when they
     * would not all fit (vanilla {@code testClearGrid} semantics: never drops items to the world
     * here). Grid input cells are menu slots 1..gridSlots (result is slot 0) in both InventoryMenu
     * (2×2) and CraftingMenu (3×3) on every version.
     *
     * @return true when the grid is empty afterwards
     */
    private static boolean returnGridToInventory(ServerPlayer player,
                                                 net.minecraft.world.inventory.AbstractContainerMenu menu,
                                                 int gridSlots) {
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 1; i <= gridSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        if (contents.isEmpty()) {
            return true;
        }
        if (!fitsInMainInventory(player.getInventory(), contents)) {
            return false;
        }
        for (int i = 1; i <= gridSlots; i++) {
            menu.getSlot(i).set(ItemStack.EMPTY);
        }
        for (ItemStack stack : contents) {
            insertIntoMainInventory(player, stack);
        }
        menu.broadcastChanges();
        return true;
    }

    /**
     * Lays one craft into the open grid from {@link #assign}'s choice of slots, ledger-admitted
     * stacks included, after returning whatever the grid held. Puts everything back and returns
     * {@link FillOutcome.Fallback} unless the result slot then shows {@code expected}.
     */
    private static FillOutcome placeFromLedger(ServerPlayer player,
                                               net.minecraft.world.inventory.AbstractContainerMenu menu,
                                               List<Ingredient> cells, ItemStack expected,
                                               ProducedLedger<Item> ledger) {
        if (!returnGridToInventory(player, menu, cells.size())) {
            return new FillOutcome.Fallback();
        }
        Inventory inventory = player.getInventory();
        Assignment assignment = assign(inventory, cells, ledger);
        if (assignment.missing() != null) {
            return new FillOutcome.Fallback();
        }
        int[] slots = assignment.slots();
        for (int cell = 0; cell < slots.length; cell++) {
            if (slots[cell] < 0) {
                continue;
            }
            ItemStack stack = inventory.getItem(slots[cell]);
            ItemStack one = stack.copyWithCount(1);
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(slots[cell], ItemStack.EMPTY);
            }
            menu.getSlot(RESULT_SLOT + 1 + cell).set(one);
        }
        inventory.setChanged();
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty() || !ItemStack.isSameItem(result, expected)) {
            returnGridToInventory(player, menu, cells.size());
            menu.broadcastFullState();
            return new FillOutcome.Fallback();
        }
        assignment.chargeTo(ledger);
        // Same reason as the vanilla path: push the grid and a fresh stateId in one packet.
        menu.broadcastFullState();
        return new FillOutcome.Filled(result.copy());
    }

    /**
     * The recipe's ingredient for each cell of a {@code width}×{@code height} grid, row-major,
     * null for an empty cell: a shaped recipe anchored top-left, anything else in order. Null when
     * the recipe does not fit. A modded shape that is not a {@code ShapedRecipe} may land wrong;
     * {@link #placeFromLedger} catches that by checking the result.
     */
    private static List<Ingredient> gridLayout(CraftingRecipe recipe, int width, int height) {
        List<Ingredient> cells = new ArrayList<>();
        for (int i = 0; i < width * height; i++) {
            cells.add(null);
        }
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            int recipeWidth = shaped.getWidth();
            if (recipeWidth <= 0 || recipeWidth > width || shaped.getHeight() > height) {
                return null;
            }
            //? if >=1.21.11 {
            /*List<Ingredient> pattern = new ArrayList<>();
            shaped.getIngredients().forEach(ingredient -> pattern.add(ingredient.orElse(null)));*/
            //?} else {
            List<Ingredient> pattern = shaped.getIngredients();
            //?}
            if (pattern.size() > recipeWidth * shaped.getHeight()) {
                return null;
            }
            for (int i = 0; i < pattern.size(); i++) {
                Ingredient ingredient = pattern.get(i);
                if (ingredient != null && !ingredient.isEmpty()) {
                    cells.set((i / recipeWidth) * width + i % recipeWidth, ingredient);
                }
            }
            return cells;
        }
        //? if >=1.21.11 {
        /*List<Ingredient> ingredients = recipe.placementInfo().ingredients();*/
        //?} else {
        List<Ingredient> ingredients = recipe.getIngredients();
        //?}
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

    /** {width, height} of the open crafting grid, or null when the menu has none. */
    private static int[] gridSize(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        //? if >=1.21.11 {
        /*if (menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu craftingMenu) {
            return new int[] {craftingMenu.getGridWidth(), craftingMenu.getGridHeight()};
        }*/
        //?} else {
        if (menu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> recipeBookMenu) {
            return new int[] {recipeBookMenu.getGridWidth(), recipeBookMenu.getGridHeight()};
        }
        //?}
        return null;
    }

    /** Non-mutating check that every stack would fit into the 36 main inventory slots together. */
    private static boolean fitsInMainInventory(Inventory inventory, List<ItemStack> stacks) {
        ItemStack[] snapshot = new ItemStack[Inventory.INVENTORY_SIZE];
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            snapshot[i] = inventory.getItem(i).copy();
        }
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            for (int i = 0; i < snapshot.length && !remaining.isEmpty(); i++) {
                ItemStack existing = snapshot[i];
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
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
                    snapshot[i] = remaining.copyWithCount(moved);
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
        //? if >=1.20.5 {
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.has(DataComponents.CUSTOM_NAME);
        //?} else {
        /*return !stack.isDamaged() && !stack.isEnchanted() && !stack.hasCustomHoverName(); // pre-components NBT name*/
        //?}
    }

    /**
     * One inventory slot per ingredient ({@code -1} for an empty cell), chosen without mutating
     * anything, or the first ingredient nothing could satisfy. A stack {@link #usableAsIngredient}
     * rejects is chosen only when no acceptable stack matches and {@code ledger} (null: never)
     * still admits its item through {@link LedgerTally#admissible}: only rejected copies above
     * those the player held before the job produced anything. Every pick is recorded in the tally,
     * which {@link Assignment#chargeTo} charges: rejected picks, plus plain picks beyond the job's
     * rejected copies of that item the pool still holds.
     */
    private static Assignment assign(Inventory inventory, List<Ingredient> ingredients, ProducedLedger<Item> ledger) {
        return assign(inventory, ingredients, ledger, null);
    }

    /** A cell {@link #assign} fills from the crafting station's storage, not an inventory slot. */
    private static final int STATION_CELL = -2;

    /**
     * {@link #assign(Inventory, List, ProducedLedger)} with an open crafting station's storage
     * ({@code station}, null for none) in the pool. The station joins the pool the planner counted:
     * {@link #preferredItems} picks each cell's item from inventory plus station stock, exactly as the
     * plan's {@link SlotAssignment} did. Within that item the player's own stacks go first, so a craft
     * only reaches into the station for what the inventory lacks:
     * <ol>
     *   <li>the preferred item from the inventory;</li>
     *   <li>the preferred item from the station (rather than an inventory alternative: taking a
     *       different item than the plan's rule chose could starve a later step the plan counted on);</li>
     *   <li>any usable inventory match;</li>
     *   <li>any of the ingredient's items from the station;</li>
     *   <li>(with a ledger) a refused inventory stack the ledger admits, the last resort as before.</li>
     * </ol>
     * Station cells are marked {@link #STATION_CELL} and summed per item in
     * {@link Assignment#stationTakes}; they count as plain picks in the tally (a station only holds
     * plain items), which can only draw the ledger down, never admit more.
     */
    private static Assignment assign(Inventory inventory, List<Ingredient> ingredients, ProducedLedger<Item> ledger,
                                     StationStorage station) {
        int[] slots = new int[ingredients.size()];
        int[] reserved = new int[Inventory.INVENTORY_SIZE];
        LedgerTally<Item> tally = new LedgerTally<>();
        Map<Item, Integer> ledgerTakes = tally.refusedTakes();
        ProducedLedger<Item> admitting = null;
        if (ledger != null) {
            countRefused(inventory, tally);
            admitting = tally.admissible(ledger);
        }
        StationPool pool = station == null ? null : new StationPool(station);
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
                slot = findMatchingSlot(inventory, ingredient, preferred[n], reserved, admitting, ledgerTakes);
            } else {
                slot = preferred[n] == null ? -1 : findPreferredSlot(inventory, ingredient, preferred[n], reserved);
                if (slot < 0) {
                    fromStation = pool.take(ingredient, preferred[n] == null ? null : List.of(preferred[n]));
                }
                if (slot < 0 && fromStation == null) {
                    slot = findUsableSlot(inventory, ingredient, reserved);
                }
                if (slot < 0 && fromStation == null) {
                    fromStation = pool.take(ingredient, CraftPlanner.slotAlternatives(ingredient));
                }
                if (slot < 0 && fromStation == null && admitting != null) {
                    slot = findRefusedSlot(inventory, ingredient, reserved, admitting, ledgerTakes);
                }
            }
            if (fromStation != null) {
                slots[n] = STATION_CELL;
                if (ledger != null) {
                    tally.picked(fromStation, false);
                }
                continue;
            }
            if (slot < 0) {
                return new Assignment(slots, tally, ingredient, pool == null ? Map.of() : pool.taken);
            }
            reserved[slot]++;
            slots[n] = slot;
            if (ledger != null) {
                ItemStack stack = inventory.getItem(slot);
                tally.picked(stack.getItem(), !usableAsIngredient(stack));
            }
        }
        return new Assignment(slots, tally, null, pool == null ? Map.of() : pool.taken);
    }

    /**
     * The station's side of one {@link #assign} call: simulated availability per item, queried lazily
     * and at most once per item (a simulated extraction is a storage lookup, not free), and what this
     * craft has claimed so far.
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
            return available.computeIfAbsent(item, i -> station.available(i, Integer.MAX_VALUE));
        }

        /** Claims one of the first {@code candidates} item the station still has and the ingredient accepts. */
        Item take(Ingredient ingredient, List<Item> candidates) {
            if (candidates == null) {
                return null;
            }
            for (Item item : candidates) {
                if (count(item) - taken.getOrDefault(item, 0) > 0 && ingredient.test(new ItemStack(item))) {
                    taken.merge(item, 1, Integer::sum);
                    return item;
                }
            }
            return null;
        }
    }

    /** Records in {@code tally} every stack in the 36 main slots that {@link #usableAsIngredient} rejects. */
    private static void countRefused(Inventory inventory, LedgerTally<Item> tally) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !usableAsIngredient(stack)) {
                tally.held(stack.getItem(), stack.getCount());
            }
        }
    }

    /**
     * {@code stationTakes}: plain items per kind the station supplies to this craft (cells marked
     * {@link #STATION_CELL}), empty without a station.
     */
    private record Assignment(int[] slots, LedgerTally<Item> tally, Ingredient missing,
                              Map<Item, Integer> stationTakes) {
        void chargeTo(ProducedLedger<Item> ledger) {
            tally.chargeTo(ledger);
        }
    }

    /**
     * Per ingredient, the item {@link SlotAssignment} picks for it from the usable stacks of the 36
     * main slots (null for a blank cell or when nothing usable fits). The solver plans every craft
     * with the same rule over the same slot lists ({@link CraftPlanner#slotAlternatives}) and counts,
     * so each craft takes what the plan counted on, not whichever stack sits first in the inventory.
     * With a crafting station open ({@code pool} non-null) its stock of every candidate item joins the
     * counts, as it joined the plan's snapshot.
     */
    private static Item[] preferredItems(Inventory inventory, List<Ingredient> ingredients, StationPool pool) {
        Map<Item, Integer> stock = new HashMap<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
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
            java.util.Set<Item> candidates = new java.util.HashSet<>();
            slots.forEach(candidates::addAll);
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
     * A stack of {@code preferred} ({@link #preferredItems}) when one matches; otherwise the first
     * match in inventory order, then (with a ledger) a refused stack the ledger admits.
     */
    private static int findMatchingSlot(Inventory inventory, Ingredient ingredient, Item preferred, int[] reserved,
                                        ProducedLedger<Item> ledger, Map<Item, Integer> ledgerTakes) {
        if (preferred != null) {
            int slot = findPreferredSlot(inventory, ingredient, preferred, reserved);
            if (slot >= 0) {
                return slot;
            }
        }
        int slot = findUsableSlot(inventory, ingredient, reserved);
        if (slot >= 0 || ledger == null) {
            return slot;
        }
        return findRefusedSlot(inventory, ingredient, reserved, ledger, ledgerTakes);
    }

    /** The first unreserved usable stack of {@code preferred} the ingredient accepts, or -1. */
    private static int findPreferredSlot(Inventory inventory, Ingredient ingredient, Item preferred, int[] reserved) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == preferred && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /** The first unreserved usable stack the ingredient accepts, in inventory order, or -1. */
    private static int findUsableSlot(Inventory inventory, Ingredient ingredient, int[] reserved) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /** The first unreserved refused stack the ledger still admits and the ingredient accepts, or -1. */
    private static int findRefusedSlot(Inventory inventory, Ingredient ingredient, int[] reserved,
                                       ProducedLedger<Item> ledger, Map<Item, Integer> ledgerTakes) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0 && !usableAsIngredient(stack)
                    && ledger.canTake(stack.getItem(), ledgerTakes.getOrDefault(stack.getItem(), 0))
                    && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Inserts into the 36 main slots only (merge into matching stacks, then empty slots),
     * dropping any overflow at the player's feet. Deliberately not
     * {@code Inventory.add}/{@code placeItemBackInInventory}: those can stash items into
     * the offhand slot, where later steps of the job would not find them.
     */
    private static void insertIntoMainInventory(ServerPlayer player, ItemStack stack) {
        insertIntoMainInventory(player, stack, null);
    }

    /**
     * As {@link #insertIntoMainInventory(ServerPlayer, ItemStack)}, but overflow goes into {@code station}
     * (an open crafting station's storage, null for none) before whatever it refuses drops at the feet.
     */
    private static void insertIntoMainInventory(ServerPlayer player, ItemStack stack, StationStorage station) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE && !stack.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                if (room > 0) {
                    int moved = Math.min(room, stack.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                }
            }
        }
        for (int i = 0; i < Inventory.INVENTORY_SIZE && !stack.isEmpty(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize())));
                stack.shrink(inventory.getItem(i).getCount());
            }
        }
        inventory.setChanged();
        if (!stack.isEmpty() && station != null) {
            stack = station.insert(stack);
        }
        if (!stack.isEmpty()) {
            // 26.3 replaced the (throwRandomly, retainOwnership) booleans with (retainOwnership,
            // Prediction); SERVER_ONLY matches vanilla's own server-side clearContainer drop.
            //? if >=26.3 {
            /*player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
            *///?} else {
            player.drop(stack, false);
            //?}
        }
    }

    private static Component describe(Ingredient ingredient) {
        //? if >=1.21.11 {
        /*return ingredient.items().findFirst().map(h -> new ItemStack(h.value()).getHoverName()).orElse(Component.literal("?"));*/
        //?} else {
        ItemStack[] options = ingredient.getItems();
        return options.length > 0 ? options[0].getHoverName() : Component.literal("?");
        //?}
    }
}
