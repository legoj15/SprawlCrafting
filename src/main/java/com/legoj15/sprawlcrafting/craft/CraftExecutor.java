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

    public sealed interface CraftResult {
        /** {@code crafted} is a display copy of what was produced. */
        record Success(ItemStack crafted) implements CraftResult {}

        /** An ingredient vanished since planning; {@code missing} names it for the player. */
        record MissingIngredient(Component missing) implements CraftResult {}

        /** Recipe no longer exists or is no longer executable (datapack reload). */
        record RecipeGone() implements CraftResult {}
    }

    public static CraftResult craftOnce(ServerPlayer player, ResourceLocation recipeId) {
        RecipeHolder<?> holder = player.server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe recipe)) {
            return new CraftResult.RecipeGone();
        }
        ItemStack result = recipe.getResultItem(player.registryAccess());
        if (result.isEmpty()) {
            return new CraftResult.RecipeGone();
        }

        // Phase 1: match every ingredient slot to an inventory slot without mutating,
        // so a shortfall discovered at slot N leaves slots 1..N-1 untouched.
        Inventory inventory = player.getInventory();
        List<Integer> chosenSlots = new ArrayList<>();
        int[] reserved = new int[Inventory.INVENTORY_SIZE];
        for (Ingredient ingredient : recipe.getIngredients()) {
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
            ItemStack stack = inventory.items.get(slot);
            Item item = stack.getItem();
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.items.set(slot, ItemStack.EMPTY);
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
        return new CraftResult.Success(crafted);
    }

    /** Vanilla recipe-book parity: never consume damaged, enchanted, or renamed stacks. */
    static boolean usableAsIngredient(ItemStack stack) {
        return !stack.isDamaged() && !stack.isEnchanted() && !stack.has(DataComponents.CUSTOM_NAME);
    }

    private static int findMatchingSlot(Inventory inventory, Ingredient ingredient, int[] reserved) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.items.get(i);
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
            ItemStack existing = inventory.items.get(i);
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
            if (inventory.items.get(i).isEmpty()) {
                inventory.items.set(i, stack.copyWithCount(Math.min(stack.getCount(), stack.getMaxStackSize())));
                stack.shrink(inventory.items.get(i).getCount());
            }
        }
        inventory.setChanged();
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    private static Component describe(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        return options.length > 0 ? options[0].getHoverName() : Component.literal("?");
    }
}
