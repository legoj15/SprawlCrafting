package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
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
 *       so planning, consumption, and insertion all see the same item pool.</li>
 *   <li>Damaged, enchanted, or renamed stacks are never consumed, matching what the
 *       vanilla recipe book refuses to auto-fill ({@code StackedContents.accountSimpleStack}).</li>
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

    public static CraftResult craftOnce(ServerPlayer player, ResourceLocation recipeId) {
        RecipeHolder<?> holder = RecipeIds.byId(player.level().getServer().getRecipeManager(), recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) {
            return new CraftResult.RecipeGone();
        }
        ItemStack result = RecipeIds.resultOf(recipe, player.registryAccess());
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1: match every ingredient slot to an inventory slot without mutating,
        // so a shortfall discovered at slot N leaves slots 1..N-1 untouched.
        Inventory inventory = player.getInventory();
        List<Integer> chosenSlots = new ArrayList<>();
        int[] reserved = new int[Inventory.INVENTORY_SIZE];
        //? if >=1.21.11 {
        /*for (Ingredient ingredient : recipe.placementInfo().ingredients()) {*/
        //?} else {
        for (Ingredient ingredient : recipe.getIngredients()) {
        //?}
            if (ingredient.isEmpty()) {
                continue;
            }
            int slot = findMatchingSlot(inventory, ingredient, reserved);
            if (slot < 0) {
                return new CraftResult.MissingIngredient(describe(ingredient));
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
        for (ItemStack remainder : remainders) {
            insertIntoMainInventory(player, remainder);
        }
        ItemStack crafted = result.copy();
        insertIntoMainInventory(player, result.copy());
        return new CraftResult.Success(crafted);
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
     * <p>The caller guarantees {@code player.containerMenu} is the open crafting grid and
     * fits the recipe (see {@code CraftQueueManager}); this re-checks the menu type defensively.
     */
    public static FillOutcome tryFillFinalGrid(ServerPlayer player, ResourceLocation recipeId) {
        RecipeHolder<?> holder = RecipeIds.byId(player.level().getServer().getRecipeManager(), recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) {
            return new FillOutcome.RecipeGone();
        }
        // Vanilla parity: the recipe book only auto-fills recipes the player has unlocked.
        //? if >=1.21.11 {
        /*if (!player.getRecipeBook().contains(holder.id())) {
            return new FillOutcome.Fallback();
        }*/
        //?} else {
        if (!player.getRecipeBook().contains(holder)) {
            return new FillOutcome.Fallback();
        }
        //?}
        ItemStack expected = RecipeIds.resultOf(recipe, player.registryAccess());
        if (expected.isEmpty()) {
            return new FillOutcome.RecipeGone();
        }

        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
        //? if >=1.21.11 {
        /*if (!(menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu craftingMenu)) {
            return new FillOutcome.Fallback();
        }
        craftingMenu.handlePlacement(false, false, holder,
                (net.minecraft.server.level.ServerLevel) player.level(), player.getInventory());*/
        //?} else {
        if (!(menu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> recipeBookMenu)) {
            return new FillOutcome.Fallback();
        }
        recipeBookMenu.handlePlacement(false, holder, player);
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
        int width;
        int height;
        //? if >=1.21.11 {
        /*if (!(menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu craftingMenu)) {
            return;
        }
        width = craftingMenu.getGridWidth();
        height = craftingMenu.getGridHeight();*/
        //?} else {
        if (!(menu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> recipeBookMenu)) {
            return;
        }
        width = recipeBookMenu.getGridWidth();
        height = recipeBookMenu.getGridHeight();
        //?}

        // Grid input cells are menu slots 1..gridSlots (result is slot 0) in both InventoryMenu
        // (2×2) and CraftingMenu (3×3) on both versions.
        int gridSlots = width * height;
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 1; i <= gridSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        if (contents.isEmpty() || !fitsInMainInventory(player.getInventory(), contents)) {
            return; // nothing to move, or vanilla-style no-op when it would not all fit
        }
        for (int i = 1; i <= gridSlots; i++) {
            menu.getSlot(i).set(ItemStack.EMPTY);
        }
        for (ItemStack stack : contents) {
            insertIntoMainInventory(player, stack);
        }
        menu.broadcastChanges();
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
    static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.has(DataComponents.CUSTOM_NAME);
    }

    private static int findMatchingSlot(Inventory inventory, Ingredient ingredient, int[] reserved) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.test(stack)) {
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
        if (!stack.isEmpty()) {
            player.drop(stack, false);
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
