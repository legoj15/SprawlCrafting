package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;

/**
 * Performs a single craft of a recipe directly against a player's live inventory: consumes one
 * item per ingredient slot, returns crafting remainders (empty buckets), and inserts the result
 * as real items (overflow drops at the player's feet). Java-8 port of the modern executor.
 *
 * <p>This is the per-step consumption point of DESIGN.md: ingredients are re-checked against
 * reality here, every half second — the plan only predicted them.
 *
 * <p>Two invariants shared with {@link CraftPlanner}:
 * <ul>
 *   <li>Only the 36 main inventory slots are read and written — never armor or offhand — so
 *       planning, consumption, and insertion all see the same item pool.</li>
 *   <li>Damaged, enchanted, or renamed stacks are never consumed, matching what the vanilla
 *       recipe book refuses to auto-fill.</li>
 * </ul>
 *
 * <p>Unlike the modern tree this port does not hand the final craft off into an open crafting
 * grid; it always auto-crafts the result into the inventory. That hand-off was a later modern
 * refinement; the core "intermediates and final become real items" behaviour is unchanged.
 */
public final class CraftExecutor {

    private CraftExecutor() {
    }

    private static final int MAIN = CraftPlanner.MAIN_INVENTORY_SIZE;

    public abstract static class CraftResult {
        private CraftResult() {
        }

        /** {@code crafted} is a display copy of what was produced. */
        public static final class Success extends CraftResult {
            private final ItemStack crafted;

            public Success(ItemStack crafted) {
                this.crafted = crafted;
            }

            public ItemStack crafted() {
                return crafted;
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

    public static CraftResult craftOnce(EntityPlayerMP player, ResourceLocation recipeId) {
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            return new CraftResult.RecipeGone();
        }
        ItemStack result = recipe.getRecipeOutput();
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1: match every ingredient slot to an inventory slot without mutating, so a
        // shortfall discovered at slot N leaves slots 1..N-1 untouched.
        InventoryPlayer inventory = player.inventory;
        List<Integer> chosenSlots = new ArrayList<Integer>();
        int[] reserved = new int[MAIN];
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == Ingredient.EMPTY) {
                continue;
            }
            int slot = findMatchingSlot(inventory, ingredient, reserved);
            if (slot < 0) {
                return new CraftResult.MissingIngredient(describe(ingredient));
            }
            reserved[slot]++;
            chosenSlots.add(slot);
        }

        // Phase 2: apply — consume, collect remainders, insert the result.
        List<ItemStack> remainders = new ArrayList<ItemStack>();
        for (int slot : chosenSlots) {
            ItemStack stack = inventory.mainInventory.get(slot);
            Item item = stack.getItem();
            if (item.hasContainerItem(stack)) {
                ItemStack remainder = item.getContainerItem(stack);
                if (!remainder.isEmpty()) {
                    remainders.add(remainder);
                }
            }
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.mainInventory.set(slot, ItemStack.EMPTY);
            }
        }
        for (ItemStack remainder : remainders) {
            insertIntoMainInventory(player, remainder);
        }
        ItemStack crafted = result.copy();
        insertIntoMainInventory(player, result.copy());
        inventory.markDirty();
        return new CraftResult.Success(crafted);
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
        int gridSlots;
        if (menu instanceof ContainerWorkbench) {
            gridSlots = 9;
        } else if (menu instanceof ContainerPlayer) {
            gridSlots = 4;
        } else if (GridContext.isModded3x3Crafter(menu)) {
            gridSlots = 9;
        } else {
            return;
        }
        // Grid input cells are container slots 1..gridSlots (result is slot 0).
        List<ItemStack> contents = new ArrayList<ItemStack>();
        for (int i = 1; i <= gridSlots; i++) {
            ItemStack stack = menu.getSlot(i).getStack();
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        if (contents.isEmpty() || !fitsInMainInventory(player.inventory, contents)) {
            return;
        }
        for (int i = 1; i <= gridSlots; i++) {
            menu.getSlot(i).putStack(ItemStack.EMPTY);
        }
        for (ItemStack stack : contents) {
            insertIntoMainInventory(player, stack);
        }
        menu.detectAndSendChanges();
    }

    /** Vanilla recipe-book parity: never consume damaged, enchanted, or renamed stacks. */
    static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isItemDamaged() && !stack.isItemEnchanted() && !stack.hasDisplayName();
    }

    private static int findMatchingSlot(InventoryPlayer inventory, Ingredient ingredient, int[] reserved) {
        for (int i = 0; i < MAIN; i++) {
            ItemStack stack = inventory.mainInventory.get(i);
            if (!stack.isEmpty() && stack.getCount() - reserved[i] > 0
                    && usableAsIngredient(stack) && ingredient.apply(stack)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Inserts into the 36 main slots only (merge into matching stacks, then empty slots), dropping
     * any overflow at the player's feet. Deliberately not {@code addItemStackToInventory}, which can
     * stash items into the offhand slot where later steps of the job would not find them.
     */
    private static void insertIntoMainInventory(EntityPlayer player, ItemStack stack) {
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
        if (!stack.isEmpty()) {
            player.dropItem(stack, false);
        }
    }

    /** Non-mutating check that every stack would fit into the 36 main inventory slots together. */
    private static boolean fitsInMainInventory(InventoryPlayer inventory, List<ItemStack> stacks) {
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
    private static boolean sameItem(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata()
                && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static String describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getMatchingStacks();
        return options.length > 0 ? options[0].getDisplayName() : "?";
    }
}
