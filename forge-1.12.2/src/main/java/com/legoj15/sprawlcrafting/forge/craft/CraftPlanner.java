package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.solver.PlannedStep;
import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver;
import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver.Result;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;

/**
 * Adapts Minecraft 1.12.2 recipes and a player inventory onto the shared
 * {@link RecipeGraphSolver}. The Java-8 counterpart of the modern tree's {@code CraftPlanner}:
 * the algorithm is identical, but items are keyed by {@link ItemKey} ({@code Item} + legacy
 * metadata) rather than {@code Item} alone, because 1.12.2 packs distinct craftables into a
 * single {@code Item} via metadata (all six plank colours are {@code minecraft:planks}).
 *
 * <p>Works on both sides: the dedicated server plans authoritatively; the client opens a
 * {@link Session} over the synced {@link CraftingManager#REGISTRY} and its own inventory to
 * classify recipes (JEI button states, recipe-book highlight) with no round trips.
 */
public final class CraftPlanner {

    private static final int MAX_DEPTH = 32;
    /** Caps backtracking on pathological graphs; vanilla plans use well under 100 attempts. */
    private static final int MAX_ATTEMPTS = 4096;
    /** The 36 main inventory slots (hotbar + storage); armor and offhand are excluded. */
    public static final int MAIN_INVENTORY_SIZE = 36;

    private CraftPlanner() {
    }

    /** How a recipe can be made right now, for recipe-viewer button states. */
    public enum Craftability {
        /** Not makeable from current raw resources. */
        UNSOLVABLE,
        /** All direct ingredients are in stock — a normal one-step craft (no deferral needed). */
        DIRECT,
        /** Makeable only by first crafting intermediates — the deferred (yellow) case. */
        DEFERRED
    }

    /** Outcome of a plan request, with enough detail for user-facing feedback. */
    public abstract static class PlanOutcome {
        private PlanOutcome() {
        }

        /** Ready to enqueue. */
        public static final class Planned extends PlanOutcome {
            private final CraftJob job;

            public Planned(CraftJob job) {
                this.job = job;
            }

            public CraftJob job() {
                return job;
            }
        }

        /** Not a crafting recipe, or a special/dynamic recipe we cannot plan. */
        public static final class Unsupported extends PlanOutcome {
        }

        /** The target itself does not fit the grid the request came from (whole-chain gating). */
        public static final class NeedsBiggerGrid extends PlanOutcome {
        }

        /** Raw resources insufficient; {@code missing} holds representative shortfall items. */
        public static final class Unsolvable extends PlanOutcome {
            private final List<ItemKey> missing;

            public Unsolvable(List<ItemKey> missing) {
                this.missing = missing;
            }

            public List<ItemKey> missing() {
                return missing;
            }
        }

        /** The recipe graph was too branchy to resolve within the search budget. */
        public static final class TooComplex extends PlanOutcome {
        }
    }

    public static PlanOutcome plan(EntityPlayer player, IRecipe target, GridContext grid) {
        return session(player, grid).plan(target);
    }

    /**
     * Plans a deferred craft of {@code result} (item + metadata), picking the first grid-fitting
     * recipe that produces it whose chain resolves. Used by the JEI "+" hook, which recovers the
     * displayed result stack but not the backing recipe object.
     */
    public static PlanOutcome planByResult(EntityPlayer player, ItemStack result, GridContext grid) {
        return session(player, grid).planByResult(result);
    }

    /**
     * The raw materials the player still needs to make {@code recipe} (see {@link Session#shortfall}).
     * Informational, so it always costs the full 3x3 chain regardless of the request grid.
     */
    public static ShortfallView shortfall(EntityPlayer player, IRecipe recipe) {
        return session(player, GridContext.CRAFTING_TABLE).shortfall(recipe);
    }

    public static Session session(EntityPlayer player, GridContext grid) {
        return new Session(grid, snapshotMaterials(player));
    }

    /**
     * The materials available to {@code player} for planning right now: the 36 main inventory slots
     * plus the open crafting container's other input slots — its crafting grid and, when a recognised
     * station is open, its connected side inventory (a Tinkers' Construct Crafting Station's adjacent
     * chest — see {@link ExternalSlots#materialSlots}). All pools are keyed by {@link ItemKey} and
     * filtered by the same {@link CraftExecutor#usableAsIngredient} predicate the executor uses, so the
     * plan never counts an item execution would refuse, and no source is held to a different standard.
     */
    private static Map<ItemKey, Integer> snapshotMaterials(EntityPlayer player) {
        Map<ItemKey, Integer> counts = snapshotInventory(player.inventory);
        for (net.minecraft.inventory.Slot slot : ExternalSlots.materialSlots(player)) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack)) {
                ItemKey key = ItemKey.of(stack);
                Integer current = counts.get(key);
                counts.put(key, (current == null ? 0 : current) + stack.getCount());
            }
        }
        return counts;
    }

    public static final class Session {
        private final GridContext grid;
        private final Map<ItemKey, List<McRecipe>> producers = new HashMap<ItemKey, List<McRecipe>>();
        private final Map<ResourceLocation, McRecipe> byId = new HashMap<ResourceLocation, McRecipe>();
        private final Map<ItemKey, Integer> inventory;
        private final RecipeGraphSolver<ResourceLocation, ItemKey> solver;
        private final Map<ResourceLocation, Craftability> classifyCache = new HashMap<ResourceLocation, Craftability>();
        private final Map<ItemKey, Boolean> resultSolvableCache = new HashMap<ItemKey, Boolean>();
        private final Map<ItemKey, ShortfallView> shortfallCache = new HashMap<ItemKey, ShortfallView>();

        private Session(GridContext grid, Map<ItemKey, Integer> inventory) {
            this.grid = grid;
            this.inventory = inventory;
            for (IRecipe recipe : CraftingManager.REGISTRY) {
                McRecipe info = McRecipe.of(recipe);
                if (info == null) {
                    continue;
                }
                byId.put(info.id, info);
                if (info.fits(grid)) {
                    List<McRecipe> list = producers.get(info.result);
                    if (list == null) {
                        list = new ArrayList<McRecipe>();
                        producers.put(info.result, list);
                    }
                    list.add(info);
                }
            }
            for (List<McRecipe> candidates : producers.values()) {
                candidates.sort(Comparator.comparing(new java.util.function.Function<McRecipe, ResourceLocation>() {
                    @Override
                    public ResourceLocation apply(McRecipe r) {
                        return r.id;
                    }
                }));
            }
            this.solver = new RecipeGraphSolver<ResourceLocation, ItemKey>(
                    new java.util.function.Function<ItemKey, List<? extends RecipeGraphSolver.RecipeInfo<ResourceLocation, ItemKey>>>() {
                        @Override
                        public List<? extends RecipeGraphSolver.RecipeInfo<ResourceLocation, ItemKey>> apply(ItemKey item) {
                            List<McRecipe> list = producers.get(item);
                            return list != null ? list : Collections.<McRecipe>emptyList();
                        }
                    },
                    new java.util.function.UnaryOperator<ItemKey>() {
                        @Override
                        public ItemKey apply(ItemKey item) {
                            return craftingRemainder(item);
                        }
                    },
                    MAX_DEPTH, MAX_ATTEMPTS);
        }

        public GridContext grid() {
            return grid;
        }

        /** Cheap repeated query for recipe viewers: can this be crafted from raws right now? */
        public boolean isSolvable(IRecipe targetRecipe) {
            return classify(targetRecipe) != Craftability.UNSOLVABLE;
        }

        /**
         * Direct vs deferred vs unsolvable, cached per recipe. A plan with a single step is direct
         * (its one step is the target with all ingredients in stock); any intermediate steps mean
         * deferral.
         */
        public Craftability classify(IRecipe targetRecipe) {
            ResourceLocation id = targetRecipe.getRegistryName();
            if (id == null) {
                return Craftability.UNSOLVABLE;
            }
            Craftability cached = classifyCache.get(id);
            if (cached != null) {
                return cached;
            }
            PlanOutcome outcome = plan(targetRecipe);
            Craftability result;
            if (!(outcome instanceof PlanOutcome.Planned)) {
                result = Craftability.UNSOLVABLE;
            } else {
                result = ((PlanOutcome.Planned) outcome).job().steps().size() == 1
                        ? Craftability.DIRECT : Craftability.DEFERRED;
            }
            classifyCache.put(id, result);
            return result;
        }

        public PlanOutcome plan(IRecipe targetRecipe) {
            McRecipe target = McRecipe.of(targetRecipe);
            if (target == null) {
                return new PlanOutcome.Unsupported();
            }
            return planTarget(target);
        }

        /**
         * Whether {@code resultStack} can be deferred-crafted from current stock (directly or via
         * intermediates), cached per result item. Drives the JEI "+" button's enabled state, which
         * is queried repeatedly — the cache keeps a stable-inventory hover cheap.
         */
        public boolean canDeferCraft(ItemStack resultStack) {
            if (resultStack.isEmpty()) {
                return false;
            }
            ItemKey key = ItemKey.of(resultStack);
            Boolean cached = resultSolvableCache.get(key);
            if (cached != null) {
                return cached.booleanValue();
            }
            boolean solvable = planByResult(resultStack) instanceof PlanOutcome.Planned;
            resultSolvableCache.put(key, Boolean.valueOf(solvable));
            return solvable;
        }

        /**
         * Plans the first grid-fitting producer of {@code resultStack} that resolves. Returns the
         * first {@code Planned}; if none plan, the first informative rejection (so the caller can
         * tell "needs a table" from "missing materials").
         */
        public PlanOutcome planByResult(ItemStack resultStack) {
            if (resultStack.isEmpty()) {
                return new PlanOutcome.Unsupported();
            }
            List<McRecipe> candidates = producers.get(ItemKey.of(resultStack));
            if (candidates == null || candidates.isEmpty()) {
                return new PlanOutcome.Unsupported();
            }
            PlanOutcome firstRejection = null;
            for (McRecipe candidate : candidates) {
                PlanOutcome outcome = planTarget(candidate);
                if (outcome instanceof PlanOutcome.Planned) {
                    return outcome;
                }
                if (firstRejection == null || firstRejection instanceof PlanOutcome.Unsupported) {
                    firstRejection = outcome;
                }
            }
            return firstRejection != null ? firstRejection : new PlanOutcome.Unsupported();
        }

        /**
         * The net raw-gatherable demand to make {@code recipe} once: the leaf items (ore, logs,
         * smelt-only) the player must still acquire, after crediting current stock and the
         * overproduction of any intermediates the chain would make. Raw-materials-only by design.
         * A recipe that doesn't fit the grid yields an empty demand list.
         */
        public ShortfallView shortfall(IRecipe recipe) {
            McRecipe target = McRecipe.of(recipe);
            return target == null ? ShortfallView.unavailable() : shortfallOf(target);
        }

        /**
         * Shortfall for the first grid-fitting producer of {@code resultStack}, cached per result
         * item (the JEI "+" error tooltip queries this repeatedly while the greyed button is hovered).
         */
        public ShortfallView shortfallByResult(ItemStack resultStack) {
            if (resultStack.isEmpty()) {
                return ShortfallView.unavailable();
            }
            ItemKey key = ItemKey.of(resultStack);
            ShortfallView cached = shortfallCache.get(key);
            if (cached != null) {
                return cached;
            }
            List<McRecipe> candidates = producers.get(key);
            ShortfallView view = candidates == null || candidates.isEmpty()
                    ? ShortfallView.unavailable() : shortfallOf(candidates.get(0));
            shortfallCache.put(key, view);
            return view;
        }

        private ShortfallView shortfallOf(McRecipe target) {
            if (!target.fits(grid)) {
                return new ShortfallView(target.resultStack, false, java.util.Collections.<ItemDemand>emptyList());
            }
            RecipeGraphSolver.ShortfallResult<ItemKey> result = solver.shortfall(target, inventory);
            List<ItemDemand> demands = new ArrayList<ItemDemand>();
            for (Map.Entry<ItemKey, Integer> entry : result.demands().entrySet()) {
                List<ItemKey> alternatives = result.alternatives().get(entry.getKey());
                if (alternatives == null || alternatives.isEmpty()) {
                    alternatives = java.util.Collections.singletonList(entry.getKey());
                }
                demands.add(new ItemDemand(alternatives, entry.getValue()));
            }
            demands.sort(Comparator.comparing(new java.util.function.Function<ItemDemand, String>() {
                @Override
                public String apply(ItemDemand demand) {
                    return demand.representative().toString();
                }
            }));
            return new ShortfallView(target.resultStack, result.approximate(), demands);
        }

        private PlanOutcome planTarget(McRecipe target) {
            if (!target.fits(grid)) {
                return new PlanOutcome.NeedsBiggerGrid();
            }
            Result<ResourceLocation, ItemKey> result = solver.solve(target, inventory);
            if (result instanceof Result.Success) {
                List<PlannedStep<ResourceLocation>> planned =
                        ((Result.Success<ResourceLocation, ItemKey>) result).steps();
                List<CraftStep> steps = new ArrayList<CraftStep>();
                for (PlannedStep<ResourceLocation> step : planned) {
                    steps.add(new CraftStep(step.recipeId(), step.crafts(), !fits2x2(step.recipeId(), target)));
                }
                return new PlanOutcome.Planned(new CraftJob(target.id, target.resultStack, steps));
            }
            Result.Failure<ResourceLocation, ItemKey> failure =
                    (Result.Failure<ResourceLocation, ItemKey>) result;
            if (failure.budgetExceeded()) {
                return new PlanOutcome.TooComplex();
            }
            return new PlanOutcome.Unsolvable(new ArrayList<ItemKey>(failure.missing()));
        }

        private boolean fits2x2(ResourceLocation recipeId, McRecipe target) {
            if (recipeId.equals(target.id)) {
                return target.fits2x2;
            }
            McRecipe recipe = byId.get(recipeId);
            return recipe == null || recipe.fits2x2;
        }
    }

    /**
     * The 36 main inventory slots as an item-count multiset, keyed by {@link ItemKey}. Armor and
     * offhand are excluded: planning must never consume worn equipment, and execution only pulls
     * from the main slots. Damaged/enchanted/renamed stacks are skipped with the same predicate
     * execution uses, so the plan never counts an item {@code CraftExecutor} would refuse.
     */
    private static Map<ItemKey, Integer> snapshotInventory(InventoryPlayer inventory) {
        Map<ItemKey, Integer> counts = new HashMap<ItemKey, Integer>();
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.mainInventory.get(i);
            if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack)) {
                ItemKey key = ItemKey.of(stack);
                Integer current = counts.get(key);
                counts.put(key, (current == null ? 0 : current) + stack.getCount());
            }
        }
        return counts;
    }

    /** What consuming one of {@code key} leaves behind (a bucket from a water bucket), or null. */
    static ItemKey craftingRemainder(ItemKey key) {
        ItemStack stack = key.toStack();
        if (stack.getItem().hasContainerItem(stack)) {
            ItemStack remainder = stack.getItem().getContainerItem(stack);
            if (!remainder.isEmpty()) {
                return ItemKey.of(remainder);
            }
        }
        return null;
    }

    /** A crafting recipe reduced to the solver's view, or null if it cannot be planned. */
    static final class McRecipe implements RecipeGraphSolver.RecipeInfo<ResourceLocation, ItemKey> {
        final ResourceLocation id;
        final List<List<ItemKey>> ingredientSlots;
        final ItemKey result;
        final int resultCount;
        final ItemStack resultStack;
        final boolean fits2x2;
        final boolean fits3x3;

        private McRecipe(ResourceLocation id, List<List<ItemKey>> ingredientSlots, ItemKey result,
                         int resultCount, ItemStack resultStack, boolean fits2x2, boolean fits3x3) {
            this.id = id;
            this.ingredientSlots = ingredientSlots;
            this.result = result;
            this.resultCount = resultCount;
            this.resultStack = resultStack;
            this.fits2x2 = fits2x2;
            this.fits3x3 = fits3x3;
        }

        boolean fits(GridContext grid) {
            return grid == GridContext.INVENTORY ? fits2x2 : fits3x3;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public List<List<ItemKey>> ingredientSlots() {
            return ingredientSlots;
        }

        @Override
        public ItemKey result() {
            return result;
        }

        @Override
        public int resultCount() {
            return resultCount;
        }

        static McRecipe of(IRecipe recipe) {
            if (recipe == null || recipe.isDynamic()) {
                return null;
            }
            ResourceLocation id = recipe.getRegistryName();
            if (id == null) {
                return null;
            }
            ItemStack output = recipe.getRecipeOutput();
            if (output.isEmpty()) {
                return null; // special/dynamic recipe with no fixed result
            }
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) {
                return null;
            }
            List<List<ItemKey>> slots = new ArrayList<List<ItemKey>>();
            for (Ingredient ingredient : ingredients) {
                if (ingredient == Ingredient.EMPTY) {
                    continue; // blank cell in a shaped recipe's grid
                }
                ItemStack[] matching = ingredient.getMatchingStacks();
                List<ItemKey> alternatives = new ArrayList<ItemKey>();
                for (ItemStack option : matching) {
                    if (option.isEmpty()) {
                        continue;
                    }
                    ItemKey key = ItemKey.of(option);
                    if (!alternatives.contains(key)) {
                        alternatives.add(key);
                    }
                }
                if (alternatives.isEmpty()) {
                    return null; // a real ingredient that resolves to nothing (e.g. empty oredict)
                }
                slots.add(alternatives);
            }
            if (slots.isEmpty()) {
                return null;
            }
            return new McRecipe(id, slots, ItemKey.of(output), output.getCount(), output.copy(),
                    recipe.canFit(2, 2), recipe.canFit(3, 3));
        }
    }
}
