package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.solver.PlannedStep;
import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver;
import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver.Result;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Adapts Minecraft recipes and the player's inventory onto the pure
 * {@link RecipeGraphSolver}. Items are keyed by {@link Item} identity (components/NBT are
 * ignored while planning; execution re-checks with {@link Ingredient#test} and fails
 * gracefully on mismatch, per DESIGN.md).
 */
public final class CraftPlanner {

    private static final int MAX_DEPTH = 32;
    /** Caps backtracking on pathological graphs; vanilla plans use well under 100 attempts. */
    private static final int MAX_ATTEMPTS = 4096;

    private CraftPlanner() {
    }

    /** Outcome of a plan request, with enough detail for user-facing feedback. */
    public sealed interface PlanOutcome {
        /** Ready to enqueue. */
        record Planned(CraftJob job) implements PlanOutcome {}

        /** Not a crafting recipe, or a special/dynamic recipe we cannot plan. */
        record Unsupported() implements PlanOutcome {}

        /** Raw resources insufficient; {@code missing} holds representative shortfall items. */
        record Unsolvable(List<Item> missing) implements PlanOutcome {}

        /** The recipe graph was too branchy to resolve within the search budget. */
        record TooComplex() implements PlanOutcome {}
    }

    public static PlanOutcome plan(ServerPlayer player, RecipeHolder<?> targetHolder) {
        HolderLookup.Provider registries = player.registryAccess();

        McRecipe target = McRecipe.of(targetHolder, registries);
        if (target == null) {
            return new PlanOutcome.Unsupported();
        }

        Map<Item, List<McRecipe>> producers = indexCraftingRecipes(player, registries);
        RecipeGraphSolver<ResourceLocation, Item> solver = new RecipeGraphSolver<>(
                item -> producers.getOrDefault(item, List.of()),
                CraftPlanner::craftingRemainder,
                MAX_DEPTH, MAX_ATTEMPTS);

        Result<ResourceLocation, Item> result = solver.solve(target, snapshotInventory(player));
        if (result instanceof Result.Success<ResourceLocation, Item> success) {
            List<CraftStep> steps = success.steps().stream()
                    .map(step -> new CraftStep(step.recipeId(), step.crafts()))
                    .toList();
            return new PlanOutcome.Planned(new CraftJob(targetHolder.id(), target.resultStack(), steps));
        }
        Result.Failure<ResourceLocation, Item> failure = (Result.Failure<ResourceLocation, Item>) result;
        if (failure.budgetExceeded()) {
            return new PlanOutcome.TooComplex();
        }
        return new PlanOutcome.Unsolvable(failure.missing().stream().toList());
    }

    /**
     * The 36 main inventory slots as an item-count multiset. Armor and offhand are
     * deliberately excluded: planning must never consume worn equipment, and execution
     * only pulls from the main slots (see {@code CraftExecutor}). Damaged/enchanted/renamed
     * stacks are skipped with the same predicate execution uses, so the plan never counts
     * an item that {@code CraftExecutor} would refuse to consume.
     */
    private static Map<Item, Integer> snapshotInventory(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.items.get(i);
            if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** All plannable crafting recipes indexed by result item, candidate lists sorted by id. */
    private static Map<Item, List<McRecipe>> indexCraftingRecipes(ServerPlayer player,
                                                                  HolderLookup.Provider registries) {
        Map<Item, List<McRecipe>> byResult = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder
                : player.server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            McRecipe info = McRecipe.of(holder, registries);
            if (info != null) {
                byResult.computeIfAbsent(info.result(), item -> new ArrayList<>()).add(info);
            }
        }
        for (List<McRecipe> candidates : byResult.values()) {
            candidates.sort(Comparator.comparing(McRecipe::id));
        }
        return byResult;
    }

    private static Item craftingRemainder(Item item) {
        return item.hasCraftingRemainingItem() ? item.getCraftingRemainingItem() : null;
    }

    /** A crafting recipe reduced to the solver's view, or null if it cannot be planned. */
    private record McRecipe(ResourceLocation id, List<List<Item>> ingredientSlots,
                            Item result, int resultCount, ItemStack resultStack)
            implements RecipeGraphSolver.RecipeInfo<ResourceLocation, Item> {

        static McRecipe of(RecipeHolder<?> holder, HolderLookup.Provider registries) {
            if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
                return null;
            }
            ItemStack resultStack = recipe.getResultItem(registries);
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (resultStack.isEmpty() || ingredients.isEmpty()) {
                return null;
            }
            List<List<Item>> slots = new ArrayList<>();
            for (Ingredient ingredient : ingredients) {
                if (ingredient.isEmpty()) {
                    continue; // blank slot in a shaped recipe's grid
                }
                List<Item> alternatives = Arrays.stream(ingredient.getItems())
                        .map(ItemStack::getItem)
                        .distinct()
                        .toList();
                if (alternatives.isEmpty()) {
                    return null; // unresolvable ingredient (e.g. empty tag)
                }
                slots.add(alternatives);
            }
            if (slots.isEmpty()) {
                return null;
            }
            return new McRecipe(holder.id(), slots, resultStack.getItem(), resultStack.getCount(), resultStack);
        }
    }
}
