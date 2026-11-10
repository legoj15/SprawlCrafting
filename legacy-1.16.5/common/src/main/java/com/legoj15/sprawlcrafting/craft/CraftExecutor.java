package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.solver.LedgerTally;
import com.legoj15.solver.ProducedLedger;
import com.legoj15.solver.SlotAssignment;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.RecipeBookContainer;
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
 * written (never armor or offhand), and damaged/enchanted/renamed stacks are never consumed, matching
 * what the vanilla recipe book refuses to auto-fill, except up to what the job's {@link ProducedLedger}
 * says it produced itself (some mods enchant an item on its first inventory tick, after insertion).
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    /** The crafting result slot is index 0 in both PlayerContainer (2×2) and WorkbenchContainer (3×3). */
    private static final int RESULT_SLOT = 0;
    private static final int MAIN_SLOTS = CraftPlanner.MAIN_SLOTS;

    /** Outcome of one craft. Java 8 stand-in for the modern sealed interface. */
    public static final class CraftResult {
        public enum Kind { SUCCESS, MISSING_INGREDIENT, RECIPE_GONE }

        private final Kind kind;
        private final ItemStack crafted;
        private final ITextComponent missing;

        private CraftResult(Kind kind, ItemStack crafted, ITextComponent missing) {
            this.kind = kind;
            this.crafted = crafted;
            this.missing = missing;
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

        public Kind kind() {
            return kind;
        }

        public ItemStack result() {
            return result;
        }
    }

    public static CraftResult craftOnce(ServerPlayerEntity player, ResourceLocation recipeId,
                                        ProducedLedger<Item> ledger) {
        IRecipe<?> recipe = RecipeIds.byId(player.getServer().getRecipeManager(), recipeId).orElse(null);
        if (!(recipe instanceof ICraftingRecipe)) {
            return new CraftResult(CraftResult.Kind.RECIPE_GONE, ItemStack.EMPTY, null);
        }
        ItemStack result = RecipeIds.resultOf(recipe);
        if (result.isEmpty()) {
            return new CraftResult(CraftResult.Kind.RECIPE_GONE, ItemStack.EMPTY, null);
        }

        // Phase 1: match every ingredient slot to an inventory slot without mutating, so a shortfall
        // discovered at slot N leaves slots 1..N-1 untouched.
        PlayerInventory inventory = player.inventory;
        Assignment assignment = assign(inventory, recipe.getIngredients(), ledger);
        if (assignment.missing != null) {
            return new CraftResult(CraftResult.Kind.MISSING_INGREDIENT, ItemStack.EMPTY,
                    describe(assignment.missing));
        }

        // Phase 2: apply — consume, return remainders, insert the result.
        List<ItemStack> remainders = new ArrayList<>();
        for (int slot : assignment.slots) {
            if (slot < 0) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            Item item = stack.getItem();
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            if (item.hasCraftingRemainingItem()) {
                remainders.add(new ItemStack(item.getCraftingRemainingItem()));
            }
        }
        inventory.setChanged();
        assignment.chargeTo(ledger);
        for (ItemStack remainder : remainders) {
            insertIntoMainInventory(player, remainder);
        }
        ItemStack crafted = result.copy();
        insertIntoMainInventory(player, result.copy());
        ledger.produced(crafted.getItem(), crafted.getCount());
        return new CraftResult(CraftResult.Kind.SUCCESS, crafted, null);
    }

    /**
     * Hands the final craft off to the crafting grid the player has open, instead of auto-crafting it:
     * drives vanilla's own recipe-book auto-fill ({@code handlePlacement} → {@code ServerRecipePlacer}),
     * which relocates anything in the grid back to the inventory, lays out the recipe, and silently
     * no-ops when the grid can't be cleared. Like vanilla it only fills unlocked recipes. Success is
     * confirmed by checking the result slot shows this recipe's output; otherwise the caller auto-crafts.
     *
     * <p>Vanilla's placement counts only undamaged, unenchanted, unnamed stacks, so when the recipe
     * needs an intermediate the job crafted that has since been altered (see {@link ProducedLedger}),
     * the grid is laid out here instead, from the same assignment {@link #craftOnce} would consume.
     */
    public static FillOutcome tryFillFinalGrid(ServerPlayerEntity player, ResourceLocation recipeId,
                                               ProducedLedger<Item> ledger) {
        IRecipe<?> recipe = RecipeIds.byId(player.getServer().getRecipeManager(), recipeId).orElse(null);
        if (!(recipe instanceof ICraftingRecipe)) {
            return new FillOutcome(FillOutcome.Kind.RECIPE_GONE, ItemStack.EMPTY);
        }
        // Vanilla parity: the recipe book only auto-fills recipes the player has unlocked.
        if (!player.getRecipeBook().contains(recipe)) {
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
        }
        ItemStack expected = RecipeIds.resultOf(recipe);
        if (expected.isEmpty()) {
            return new FillOutcome(FillOutcome.Kind.RECIPE_GONE, ItemStack.EMPTY);
        }
        Container menu = player.containerMenu;
        if (!(menu instanceof RecipeBookContainer)) {
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
        }
        RecipeBookContainer<?> recipeBookMenu = (RecipeBookContainer<?>) menu;
        List<Ingredient> cells = gridLayout(recipe, recipeBookMenu.getGridWidth(), recipeBookMenu.getGridHeight());
        if (cells != null && assign(player.inventory, cells, null).missing != null
                && assign(player.inventory, cells, ledger).missing == null) {
            return placeFromLedger(player, menu, cells, expected, ledger);
        }
        recipeBookMenu.handlePlacement(false, recipe, player);

        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty() || !ItemStack.isSame(result, expected)) {
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
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
     * included, after returning whatever the grid held. Puts everything back and reports FALLBACK
     * unless the result slot then shows {@code expected}.
     */
    private static FillOutcome placeFromLedger(ServerPlayerEntity player, Container menu, List<Ingredient> cells,
                                               ItemStack expected, ProducedLedger<Item> ledger) {
        if (!returnGridToInventory(player, menu, cells.size())) {
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
        }
        PlayerInventory inventory = player.inventory;
        Assignment assignment = assign(inventory, cells, ledger);
        if (assignment.missing != null) {
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
        }
        int[] slots = assignment.slots;
        for (int cell = 0; cell < slots.length; cell++) {
            if (slots[cell] < 0) {
                continue;
            }
            ItemStack stack = inventory.getItem(slots[cell]);
            ItemStack one = copyWithCount(stack, 1);
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(slots[cell], ItemStack.EMPTY);
            }
            menu.getSlot(RESULT_SLOT + 1 + cell).set(one);
        }
        inventory.setChanged();
        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty() || !ItemStack.isSame(result, expected)) {
            returnGridToInventory(player, menu, cells.size());
            syncWholeMenu(player, menu);
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
        }
        assignment.chargeTo(ledger);
        syncWholeMenu(player, menu);
        return new FillOutcome(FillOutcome.Kind.FILLED, result.copy());
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
     */
    public static void clearOpenCraftingGrid(ServerPlayerEntity player) {
        if (player.isSpectator()) {
            return;
        }
        Container menu = player.containerMenu;
        if (!(menu instanceof RecipeBookContainer)) {
            return;
        }
        RecipeBookContainer<?> recipeBookMenu = (RecipeBookContainer<?>) menu;
        int width = recipeBookMenu.getGridWidth();
        int height = recipeBookMenu.getGridHeight();
        if (width < 2 || height < 2) {
            return; // furnace-style menu: no crafting grid
        }
        returnGridToInventory(player, menu, width * height);
    }

    /**
     * Moves the grid's contents into the main inventory, or leaves them all in place when they would
     * not all fit. Grid input cells are menu slots 1..gridSlots (result is slot 0) in both crafting menus.
     *
     * @return true when the grid is empty afterwards
     */
    private static boolean returnGridToInventory(ServerPlayerEntity player, Container menu, int gridSlots) {
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
        if (!fitsInMainInventory(player.inventory, contents)) {
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
    static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.hasCustomHoverName();
    }

    /**
     * One inventory slot per ingredient ({@code -1} for an empty cell), chosen without mutating anything,
     * or the first ingredient nothing could satisfy. A stack {@link #usableAsIngredient} rejects is chosen
     * only when no acceptable stack matches and {@code ledger} (null: never) still admits its item through
     * {@link LedgerTally#admissible}: only rejected copies above those the player held before the job
     * produced anything. Every pick is recorded in the tally, which {@link Assignment#chargeTo} charges:
     * rejected picks, plus plain picks beyond the job's rejected copies of that item the pool still holds.
     */
    private static Assignment assign(PlayerInventory inventory, List<Ingredient> ingredients,
                                     ProducedLedger<Item> ledger) {
        int[] slots = new int[ingredients.size()];
        int[] reserved = new int[MAIN_SLOTS];
        LedgerTally<Item> tally = new LedgerTally<>();
        Map<Item, Integer> ledgerTakes = tally.refusedTakes();
        ProducedLedger<Item> admitting = null;
        if (ledger != null) {
            countRefused(inventory, tally);
            admitting = tally.admissible(ledger);
        }
        Item[] preferred = preferredItems(inventory, ingredients);
        for (int n = 0; n < slots.length; n++) {
            Ingredient ingredient = ingredients.get(n);
            if (ingredient == null || ingredient.isEmpty()) {
                slots[n] = -1;
                continue;
            }
            int slot = findMatchingSlot(inventory, ingredient, preferred[n], reserved, admitting, ledgerTakes);
            if (slot < 0) {
                return new Assignment(slots, tally, ingredient);
            }
            reserved[slot]++;
            slots[n] = slot;
            if (ledger != null) {
                ItemStack stack = inventory.getItem(slot);
                tally.picked(stack.getItem(), !usableAsIngredient(stack));
            }
        }
        return new Assignment(slots, tally, null);
    }

    /** Records in {@code tally} every stack in the 36 main slots that {@link #usableAsIngredient} rejects. */
    private static void countRefused(PlayerInventory inventory, LedgerTally<Item> tally) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !usableAsIngredient(stack)) {
                tally.held(stack.getItem(), stack.getCount());
            }
        }
    }

    private static final class Assignment {
        final int[] slots;
        final LedgerTally<Item> tally;
        final Ingredient missing;

        Assignment(int[] slots, LedgerTally<Item> tally, Ingredient missing) {
            this.slots = slots;
            this.tally = tally;
            this.missing = missing;
        }

        void chargeTo(ProducedLedger<Item> ledger) {
            tally.chargeTo(ledger);
        }
    }

    /**
     * Per ingredient, the item {@link SlotAssignment} picks for it from the usable stacks of the 36
     * main slots (null for a blank cell or when nothing usable fits). The solver plans every craft
     * with the same rule over the same slot lists ({@link CraftPlanner#slotAlternatives}) and counts,
     * so each craft takes what the plan counted on, not whichever stack sits first in the inventory.
     */
    private static Item[] preferredItems(PlayerInventory inventory, List<Ingredient> ingredients) {
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
    private static int findMatchingSlot(PlayerInventory inventory, Ingredient ingredient, Item preferred,
                                        int[] reserved, ProducedLedger<Item> ledger,
                                        Map<Item, Integer> ledgerTakes) {
        if (preferred != null) {
            for (int i = 0; i < MAIN_SLOTS; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == preferred && stack.getCount() - reserved[i] > 0
                        && usableAsIngredient(stack) && ingredient.test(stack)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.test(stack)) {
                return i;
            }
        }
        if (ledger == null) {
            return -1;
        }
        for (int i = 0; i < MAIN_SLOTS; i++) {
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
     * Inserts into the 36 main slots only (merge into matching stacks, then empty slots), dropping any
     * overflow at the player's feet. Deliberately not {@code PlayerInventory.add}: that can stash items
     * into the offhand slot, where later steps of the job would not find them.
     */
    private static void insertIntoMainInventory(ServerPlayerEntity player, ItemStack stack) {
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
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    private static ITextComponent describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        return options.length > 0 ? options[0].getHoverName() : new StringTextComponent("?");
    }
}
