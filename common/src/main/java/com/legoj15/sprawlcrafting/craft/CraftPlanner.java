package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Adapts Minecraft recipes and a player inventory onto the pure
 * {@link RecipeGraphSolver}. Items are keyed by {@link Item} identity (components/NBT are
 * ignored while planning; execution re-checks with {@link Ingredient#test} and fails
 * gracefully on mismatch, per DESIGN.md).
 *
 * <p>Works on both sides: the server plans authoritatively from {@link ServerPlayer}
 * state; the client opens a {@link Session} over its synced RecipeManager and inventory
 * mirror to compute yellow-outline solvability and click previews with no round trips.
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

        /** The target itself does not fit the grid the request came from (DESIGN.md: whole-chain gating). */
        record NeedsBiggerGrid() implements PlanOutcome {}

        /** Raw resources insufficient; {@code missing} holds representative shortfall items. */
        record Unsolvable(List<Item> missing) implements PlanOutcome {}

        /** The recipe graph was too branchy to resolve within the search budget. */
        record TooComplex() implements PlanOutcome {}
    }

    public static PlanOutcome plan(ServerPlayer player, RecipeHolder<?> targetHolder, GridContext grid) {
        // Cheap reject before building the producer index, so spammed unplannable
        // requests (special/non-crafting/wrong-grid recipes) cost almost nothing.
        if (McRecipe.of(targetHolder, player.registryAccess()) == null) {
            return new PlanOutcome.Unsupported();
        }
        return session(player.server.getRecipeManager(), player.registryAccess(),
                player.getInventory(), grid).plan(targetHolder);
    }

    /**
     * Snapshots the inventory and indexes all grid-fitting crafting recipes once, so many
     * plan/solvability queries (the client's recipe book pass) share the expensive setup.
     * A session is a point-in-time view: discard it when the inventory or recipes change.
     *
     * <p>The expensive per-grid producer index (which only changes on datapack reload) is
     * cached per {@link RecipeManager}; only the cheap inventory snapshot is per call.
     */
    public static Session session(RecipeManager recipes, HolderLookup.Provider registries,
                                  Inventory inventory, GridContext grid) {
        return new Session(recipes, registries, grid,
                ProducerIndex.forGrid(recipes, registries, grid), snapshotInventory(inventory));
    }

    /**
     * Per-RecipeManager, per-grid cache of the producer index. The RecipeManager instance
     * is replaced wholesale on datapack reload (server and client alike), so keying on its
     * identity makes the cache self-invalidating; weak keys let stale managers be collected.
     * Keyed by identity so the integrated server's and client's distinct managers don't
     * evict each other in singleplayer.
     */
    private static final class ProducerIndex {
        private static final Map<RecipeManager, Map<GridContext, Map<Item, List<McRecipe>>>> CACHE =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        static Map<Item, List<McRecipe>> forGrid(RecipeManager recipes,
                                                 HolderLookup.Provider registries,
                                                 GridContext grid) {
            Map<GridContext, Map<Item, List<McRecipe>>> byGrid =
                    CACHE.computeIfAbsent(recipes, r -> java.util.Collections.synchronizedMap(
                            new java.util.EnumMap<>(GridContext.class)));
            return byGrid.computeIfAbsent(grid, g -> indexCraftingRecipes(recipes, registries, g));
        }
    }

    public static final class Session {
        private final RecipeManager recipes;
        private final HolderLookup.Provider registries;
        private final GridContext grid;
        private final Map<Item, List<McRecipe>> producers;
        private final Map<Item, Integer> inventory;
        private final RecipeGraphSolver<ResourceLocation, Item> solver;
        private final Map<ResourceLocation, Boolean> solvableCache = new HashMap<>();

        private Session(RecipeManager recipes, HolderLookup.Provider registries, GridContext grid,
                        Map<Item, List<McRecipe>> producers, Map<Item, Integer> inventory) {
            this.recipes = recipes;
            this.registries = registries;
            this.grid = grid;
            this.producers = producers;
            this.inventory = inventory;
            this.solver = new RecipeGraphSolver<>(
                    item -> producers.getOrDefault(item, List.of()),
                    CraftPlanner::craftingRemainder,
                    MAX_DEPTH, MAX_ATTEMPTS);
        }

        public GridContext grid() {
            return grid;
        }

        /** Cheap repeated query for the recipe book: can this be crafted from raws right now? */
        public boolean isSolvable(RecipeHolder<?> targetHolder) {
            return solvableCache.computeIfAbsent(targetHolder.id(),
                    id -> plan(targetHolder) instanceof PlanOutcome.Planned);
        }

        public PlanOutcome plan(RecipeHolder<?> targetHolder) {
            McRecipe target = McRecipe.of(targetHolder, registries);
            if (target == null) {
                return new PlanOutcome.Unsupported();
            }
            if (!target.fits(grid)) {
                return new PlanOutcome.NeedsBiggerGrid();
            }

            Result<ResourceLocation, Item> result = solver.solve(target, inventory);
            if (result instanceof Result.Success<ResourceLocation, Item> success) {
                List<CraftStep> steps = success.steps().stream()
                        .map(step -> new CraftStep(step.recipeId(), step.crafts(),
                                !fits2x2(step.recipeId(), target)))
                        .toList();
                return new PlanOutcome.Planned(new CraftJob(targetHolder.id(), target.resultStack(), steps));
            }
            Result.Failure<ResourceLocation, Item> failure = (Result.Failure<ResourceLocation, Item>) result;
            if (failure.budgetExceeded()) {
                return new PlanOutcome.TooComplex();
            }
            return new PlanOutcome.Unsolvable(failure.missing().stream().toList());
        }

        private boolean fits2x2(ResourceLocation recipeId, McRecipe target) {
            if (recipeId.equals(target.id())) {
                return target.fits2x2();
            }
            return recipes.byKey(recipeId)
                    .map(h -> h.value() instanceof CraftingRecipe r && r.canCraftInDimensions(2, 2))
                    .orElse(true);
        }
    }

    /**
     * The 36 main inventory slots as an item-count multiset. Armor and offhand are
     * deliberately excluded: planning must never consume worn equipment, and execution
     * only pulls from the main slots (see {@code CraftExecutor}). Damaged/enchanted/renamed
     * stacks are skipped with the same predicate execution uses, so the plan never counts
     * an item that {@code CraftExecutor} would refuse to consume.
     */
    private static Map<Item, Integer> snapshotInventory(Inventory inventory) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.items.get(i);
            if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * All plannable crafting recipes that fit the request grid, indexed by result item,
     * candidate lists sorted by id. Whole-chain gating happens here: a 3×3-only recipe
     * simply does not exist as a producer for a request made from the 2×2 inventory grid.
     */
    private static Map<Item, List<McRecipe>> indexCraftingRecipes(RecipeManager recipes,
                                                                  HolderLookup.Provider registries,
                                                                  GridContext grid) {
        Map<Item, List<McRecipe>> byResult = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
            McRecipe info = McRecipe.of(holder, registries);
            if (info != null && info.fits(grid)) {
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
                            Item result, int resultCount, ItemStack resultStack,
                            boolean fits2x2, boolean fits3x3)
            implements RecipeGraphSolver.RecipeInfo<ResourceLocation, Item> {

        boolean fits(GridContext grid) {
            return grid == GridContext.INVENTORY ? fits2x2 : fits3x3;
        }

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
            return new McRecipe(holder.id(), slots, resultStack.getItem(), resultStack.getCount(),
                    resultStack, recipe.canCraftInDimensions(2, 2), recipe.canCraftInDimensions(3, 3));
        }
    }
}
