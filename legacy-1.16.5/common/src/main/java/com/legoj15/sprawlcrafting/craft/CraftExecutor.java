package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.RecipeBookContainer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ICraftingRecipe;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
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
 * what the vanilla recipe book refuses to auto-fill.
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

    public static CraftResult craftOnce(ServerPlayerEntity player, ResourceLocation recipeId) {
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
        List<Integer> chosenSlots = new ArrayList<>();
        int[] reserved = new int[MAIN_SLOTS];
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            int slot = findMatchingSlot(inventory, ingredient, reserved);
            if (slot < 0) {
                return new CraftResult(CraftResult.Kind.MISSING_INGREDIENT, ItemStack.EMPTY, describe(ingredient));
            }
            reserved[slot]++;
            chosenSlots.add(slot);
        }

        // Phase 2: apply — consume, return remainders, insert the result.
        List<ItemStack> remainders = new ArrayList<>();
        for (int slot : chosenSlots) {
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
        for (ItemStack remainder : remainders) {
            insertIntoMainInventory(player, remainder);
        }
        ItemStack crafted = result.copy();
        insertIntoMainInventory(player, result.copy());
        return new CraftResult(CraftResult.Kind.SUCCESS, crafted, null);
    }

    /**
     * Hands the final craft off to the crafting grid the player has open, instead of auto-crafting it:
     * drives vanilla's own recipe-book auto-fill ({@code handlePlacement} → {@code ServerRecipePlacer}),
     * which relocates anything in the grid back to the inventory, lays out the recipe, and silently
     * no-ops when the grid can't be cleared. Like vanilla it only fills unlocked recipes. Success is
     * confirmed by checking the result slot shows this recipe's output; otherwise the caller auto-crafts.
     */
    public static FillOutcome tryFillFinalGrid(ServerPlayerEntity player, ResourceLocation recipeId) {
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
        ((RecipeBookContainer<?>) menu).handlePlacement(false, recipe, player);

        ItemStack result = menu.getSlot(RESULT_SLOT).getItem();
        if (result.isEmpty() || !ItemStack.isSame(result, expected)) {
            return new FillOutcome(FillOutcome.Kind.FALLBACK, ItemStack.EMPTY);
        }
        // We mutated the menu outside any client click, from the end-of-tick hook (after this tick's
        // broadcastChanges already ran). Push every slot now so the grid renders complete immediately.
        menu.broadcastChanges();
        player.refreshContainer(menu, menu.getItems());
        return new FillOutcome(FillOutcome.Kind.FILLED, result.copy());
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
        // Grid input cells are menu slots 1..gridSlots (result is slot 0) in both crafting menus.
        int gridSlots = width * height;
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 1; i <= gridSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        if (contents.isEmpty() || !fitsInMainInventory(player.inventory, contents)) {
            return;
        }
        for (int i = 1; i <= gridSlots; i++) {
            menu.getSlot(i).set(ItemStack.EMPTY);
        }
        for (ItemStack stack : contents) {
            insertIntoMainInventory(player, stack);
        }
        menu.broadcastChanges();
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

    private static int findMatchingSlot(PlayerInventory inventory, Ingredient ingredient, int[] reserved) {
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
