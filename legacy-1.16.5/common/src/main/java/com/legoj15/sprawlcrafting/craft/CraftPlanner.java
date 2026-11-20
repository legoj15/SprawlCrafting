package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import com.google.common.collect.ImmutableList;
import com.legoj15.solver.PlannedStep;
import com.legoj15.solver.RecipeGraphSolver;
import com.legoj15.solver.SlotLayout;

import net.minecraft.util.registry.Registry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ICraftingRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeManager;

/**
 * Adapts 1.16.5 recipes and a player inventory onto the pure {@link RecipeGraphSolver}. Items are
 * keyed by {@link Item} identity (NBT is ignored while planning; execution re-checks with
 * {@link Ingredient#test} and fails gracefully on mismatch, per DESIGN.md).
 *
 * <p>Works on both sides: the server plans authoritatively from {@link ServerPlayerEntity} state; the client
 * opens a {@link Session} over its synced RecipeManager and inventory mirror to compute yellow-outline
 * solvability and click previews with no round trips (1.16.5 clients receive full recipe data).
 */
public final class CraftPlanner {

    private static final int MAX_DEPTH = 32;
    /** Caps backtracking on pathological graphs; vanilla plans use well under 100 attempts. */
    private static final int MAX_ATTEMPTS = 4096;
    /** The 36 main inventory slots (hotbar + storage); armor/offhand are never planned or consumed. */
    public static final int MAIN_SLOTS = 36;

    private CraftPlanner() {
    }

    /** How a recipe can be made right now, for recipe-viewer button states. */
    public enum Craftability {
        /** Not makeable from current raw resources. */
        UNSOLVABLE,
        /** All direct ingredients are in stock — a normal one-step craft. */
        DIRECT,
        /** Makeable only by first crafting intermediates — the deferred (yellow) case. */
        DEFERRED
    }

    /** Outcome of a plan request. Java 8 stand-in for the modern sealed interface. */
    public static final class PlanOutcome {
        public enum Kind { PLANNED, UNSUPPORTED, NEEDS_BIGGER_GRID, UNSOLVABLE, TOO_COMPLEX }

        private final Kind kind;
        private final CraftJob job;
        private final List<Item> missing;

        private PlanOutcome(Kind kind, CraftJob job, List<Item> missing) {
            this.kind = kind;
            this.job = job;
            this.missing = missing;
        }

        static PlanOutcome planned(CraftJob job) {
            return new PlanOutcome(Kind.PLANNED, job, ImmutableList.<Item>of());
        }

        public static PlanOutcome of(Kind kind) {
            return new PlanOutcome(kind, null, ImmutableList.<Item>of());
        }

        static PlanOutcome unsolvable(List<Item> missing) {
            return new PlanOutcome(Kind.UNSOLVABLE, null, missing);
        }

        public Kind kind() {
            return kind;
        }

        public boolean isPlanned() {
            return kind == Kind.PLANNED;
        }

        /** The job, only for {@link Kind#PLANNED}. */
        public CraftJob job() {
            return job;
        }

        /** Representative shortfall items, only for {@link Kind#UNSOLVABLE}. */
        public List<Item> missing() {
            return missing;
        }
    }

    public static PlanOutcome plan(ServerPlayerEntity player, IRecipe<?> target, GridContext grid) {
        return plan(player, target, grid, Collections.<Item, Long>emptyMap());
    }

    /**
     * Plans against the player's 36 main slots plus {@code station}: the plain-item counts of an open
     * crafting station's storage ({@link StationStorage#counts()}), empty for none.
     */
    public static PlanOutcome plan(ServerPlayerEntity player, IRecipe<?> target, GridContext grid,
                                   Map<Item, Long> station) {
        return plan(player, target, grid, station, false);
    }

    /**
     * {@link #plan(ServerPlayerEntity, IRecipe, GridContext, Map)} with the 36 main slots' protected
     * stacks (enchanted and/or renamed, never damaged; {@link CraftExecutor#isProtected}) counted as
     * material too — the player-confirmed fallback (issue #40) for recipes the plain pool cannot make.
     * The caller that planned this way marks the job {@code CraftJob#setUsesProtected}, which is what
     * lets the executor pick those stacks.
     */
    public static PlanOutcome plan(ServerPlayerEntity player, IRecipe<?> target, GridContext grid,
                                   Map<Item, Long> station, boolean includeProtected) {
        // Cheap reject before building the producer index, so spammed unplannable requests cost little.
        if (McRecipe.of(target) == null) {
            return PlanOutcome.of(PlanOutcome.Kind.UNSUPPORTED);
        }
        return session(player.getServer().getRecipeManager(), player.inventory, grid, station,
                includeProtected).plan(target);
    }

    /**
     * The raw materials the player still needs to make {@code target}; an open station's storage counts
     * as material already gathered. Returns {@link ShortfallView#unavailable()} for unplannable recipes.
     */
    public static ShortfallView shortfall(ServerPlayerEntity player, IRecipe<?> target, GridContext grid) {
        if (McRecipe.of(target) == null) {
            return ShortfallView.unavailable();
        }
        StationStorage station = StationStorages.open(player);
        return session(player.getServer().getRecipeManager(), player.inventory, grid,
                station == null ? Collections.<Item, Long>emptyMap() : station.counts()).shortfall(target);
    }

    /**
     * Snapshots the inventory and indexes all grid-fitting crafting recipes once, so many queries (the
     * client's recipe book pass) share the expensive setup. A session is a point-in-time view: discard
     * it when the inventory or recipes change. The producer index (which only changes on datapack
     * reload) is cached per {@link RecipeManager}; only the inventory snapshot is per call.
     */
    public static Session session(RecipeManager recipes, PlayerInventory inventory, GridContext grid) {
        return session(recipes, inventory, grid, Collections.<Item, Long>emptyMap());
    }

    /**
     * {@link #session(RecipeManager, PlayerInventory, GridContext)} with an open crafting station's
     * plain-item counts ({@link StationStorage}) added to the pool; empty for none.
     */
    public static Session session(RecipeManager recipes, PlayerInventory inventory, GridContext grid,
                                  Map<Item, Long> station) {
        return session(recipes, inventory, grid, station, false);
    }

    /**
     * {@link #session(RecipeManager, PlayerInventory, GridContext, Map)} with the 36 main slots'
     * protected stacks counted too ({@code includeProtected}, issue #40). A recipe such a session
     * plans is NOT plain-craftable: the caller must remember the distinction (the client classifies
     * it deferred, the confirming packet is the protected one) and mark the job it hands on.
     */
    public static Session session(RecipeManager recipes, PlayerInventory inventory, GridContext grid,
                                  Map<Item, Long> station, boolean includeProtected) {
        return new Session(recipes, grid, ProducerIndex.forGrid(recipes, grid),
                snapshotInventory(inventory, station, includeProtected));
    }

    /**
     * Drops the cached producer index for {@code recipes}. The 1.16.5 client mutates its single
     * RecipeManager in place on a datapack reload ({@code ClientPacketListener.handleUpdateRecipes} →
     * {@code replaceRecipes}), which the identity-keyed cache cannot see; servers replace the instance.
     */
    public static void invalidateProducerIndex(RecipeManager recipes) {
        ProducerIndex.CACHE.remove(recipes);
    }

    /** Per-RecipeManager, per-grid cache of the producer index (weak keys, identity semantics). */
    private static final class ProducerIndex {
        private static final Map<RecipeManager, Map<GridContext, Map<Item, List<McRecipe>>>> CACHE =
                Collections.synchronizedMap(new WeakHashMap<RecipeManager, Map<GridContext, Map<Item, List<McRecipe>>>>());

        static Map<Item, List<McRecipe>> forGrid(RecipeManager recipes, GridContext grid) {
            Map<GridContext, Map<Item, List<McRecipe>>> byGrid;
            synchronized (CACHE) {
                byGrid = CACHE.get(recipes);
                if (byGrid == null) {
                    byGrid = Collections.synchronizedMap(
                            new EnumMap<GridContext, Map<Item, List<McRecipe>>>(GridContext.class));
                    CACHE.put(recipes, byGrid);
                }
            }
            synchronized (byGrid) {
                Map<Item, List<McRecipe>> index = byGrid.get(grid);
                if (index == null) {
                    index = indexCraftingRecipes(recipes, grid);
                    byGrid.put(grid, index);
                }
                return index;
            }
        }
    }

    public static final class Session {
        private final RecipeManager recipes;
        private final GridContext grid;
        private final Map<Item, Integer> inventory;
        private final RecipeGraphSolver<ResourceLocation, Item> solver;
        private final Map<ResourceLocation, Craftability> classifyCache = new HashMap<>();

        private Session(RecipeManager recipes, GridContext grid,
                        final Map<Item, List<McRecipe>> producers, Map<Item, Integer> inventory) {
            this.recipes = recipes;
            this.grid = grid;
            this.inventory = inventory;
            this.solver = new RecipeGraphSolver<ResourceLocation, Item>(
                    item -> {
                        List<McRecipe> list = producers.get(item);
                        return list != null ? list : ImmutableList.<McRecipe>of();
                    },
                    CraftPlanner::craftingRemainder,
                    MAX_DEPTH, MAX_ATTEMPTS);
        }

        public GridContext grid() {
            return grid;
        }

        /** Cheap repeated query for the recipe book: can this be crafted from raws right now? */
        public boolean isSolvable(IRecipe<?> target) {
            return classify(target) != Craftability.UNSOLVABLE;
        }

        /**
         * Direct vs deferred vs unsolvable, cached per recipe. A plan with a single step is direct —
         * its one step is the target with all ingredients in stock; any intermediate steps mean deferral.
         */
        public Craftability classify(IRecipe<?> target) {
            Craftability cached = classifyCache.get(target.getId());
            if (cached != null) {
                return cached;
            }
            PlanOutcome outcome = plan(target);
            Craftability result;
            if (!outcome.isPlanned()) {
                result = Craftability.UNSOLVABLE;
            } else {
                result = outcome.job().steps().size() == 1 ? Craftability.DIRECT : Craftability.DEFERRED;
            }
            classifyCache.put(target.getId(), result);
            return result;
        }

        public PlanOutcome plan(IRecipe<?> targetRecipe) {
            McRecipe target = McRecipe.of(targetRecipe);
            if (target == null) {
                return PlanOutcome.of(PlanOutcome.Kind.UNSUPPORTED);
            }
            if (!target.fits(grid)) {
                return PlanOutcome.of(PlanOutcome.Kind.NEEDS_BIGGER_GRID);
            }
            RecipeGraphSolver.Result<ResourceLocation, Item> result = solver.solve(target, inventory);
            if (result instanceof RecipeGraphSolver.Result.Success) {
                RecipeGraphSolver.Result.Success<ResourceLocation, Item> success =
                        (RecipeGraphSolver.Result.Success<ResourceLocation, Item>) result;
                List<PlannedStep<ResourceLocation>> planned = success.steps();
                List<CraftStep> steps = new ArrayList<>();
                for (int i = 0; i < planned.size(); i++) {
                    PlannedStep<ResourceLocation> step = planned.get(i);
                    steps.add(new CraftStep(step.recipeId(), step.crafts(), !fits2x2(step.recipeId(), target),
                            success.layouts().get(i)));
                }
                return PlanOutcome.planned(new CraftJob(targetRecipe.getId(), target.resultStack, steps));
            }
            RecipeGraphSolver.Result.Failure<ResourceLocation, Item> failure =
                    (RecipeGraphSolver.Result.Failure<ResourceLocation, Item>) result;
            if (failure.budgetExceeded()) {
                return PlanOutcome.of(PlanOutcome.Kind.TOO_COMPLEX);
            }
            return PlanOutcome.unsolvable(new ArrayList<>(failure.missing()));
        }

        /**
         * The net raw-gatherable demand to make {@code targetRecipe} once, mapping the solver's leaf
         * items back to registry ids (sorted for a deterministic order). A recipe that doesn't fit the
         * grid yields an empty demand list (the screen still titles itself from the target).
         */
        public ShortfallView shortfall(IRecipe<?> targetRecipe) {
            McRecipe target = McRecipe.of(targetRecipe);
            if (target == null) {
                return ShortfallView.unavailable();
            }
            ResourceLocation targetId = Registry.ITEM.getKey(target.result());
            if (!target.fits(grid)) {
                return new ShortfallView(targetId, target.resultCount(), false, false, ImmutableList.<ItemDemand>of());
            }
            RecipeGraphSolver.ShortfallResult<Item> result = solver.shortfall(target, inventory);
            List<ItemDemand> demands = new ArrayList<>();
            // demandsLong, not the int-clamped demands(): batched gather lists exceed Integer.MAX_VALUE.
            for (Map.Entry<Item, Long> e : result.demandsLong().entrySet()) {
                List<Item> altItems = result.alternatives().get(e.getKey());
                if (altItems == null) {
                    altItems = ImmutableList.of(e.getKey());
                }
                List<ResourceLocation> altIds = new ArrayList<>();
                for (Item alt : altItems) {
                    altIds.add(Registry.ITEM.getKey(alt));
                }
                demands.add(new ItemDemand(altIds, e.getValue()));
            }
            demands.sort(Comparator.comparing(d -> d.items().get(0).toString()));
            return new ShortfallView(targetId, target.resultCount(), result.approximate(), result.saturated(),
                    demands);
        }

        private boolean fits2x2(ResourceLocation recipeId, McRecipe target) {
            if (recipeId.equals(target.id())) {
                return target.fits2x2;
            }
            Optional<? extends IRecipe<?>> recipe = RecipeIds.byId(recipes, recipeId);
            if (!recipe.isPresent()) {
                return true;
            }
            return recipe.get() instanceof ICraftingRecipe && recipe.get().canCraftInDimensions(2, 2);
        }
    }

    /**
     * The 36 main inventory slots as an item-count multiset. Armor and offhand are deliberately
     * excluded: planning must never consume worn equipment, and execution only pulls from the main
     * slots. Damaged stacks are always skipped; enchanted/renamed (protected, issue #40) stacks are
     * skipped too unless {@code includeProtected} — the player-confirmed tier, mirroring exactly what
     * the executor may pick for a job planned with it ({@link CraftExecutor#usableAsIngredient}).
     *
     * <p>{@code station} adds an open crafting station's plain-item counts (already usable, see
     * {@link StationStorage}); the solver counts in {@code int}, so each total is clamped at
     * {@link Integer#MAX_VALUE} (an ME network can hold far more).
     */
    private static Map<Item, Integer> snapshotInventory(PlayerInventory inventory, Map<Item, Long> station,
                                                        boolean includeProtected) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack, includeProtected)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<Item, Long> entry : station.entrySet()) {
            long count = entry.getValue();
            if (count > 0) {
                counts.merge(entry.getKey(), (int) Math.min(count, Integer.MAX_VALUE),
                        (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
            }
        }
        return counts;
    }

    /**
     * Whether any of the 36 main slots holds a protected stack (enchanted and/or renamed, never
     * damaged; {@link CraftExecutor#isProtected}). A cheap gate so callers skip the second,
     * protected planning pass (issue #40) when there is nothing for it to add.
     */
    public static boolean hasProtected(PlayerInventory inventory) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            if (CraftExecutor.isProtected(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * One representative protected stack (count 1, hover name and enchantments intact) per item the
     * plan of {@code job} will consume protected copies of, in slot order: for each item with
     * protected copies among the 36 slots, the plan's net consumption — per step, its layouts'
     * occurrences (any protected alternative, when the step carries no layout: which of them the
     * executor picks is not decided yet) less what a step produces of it — counted above the plain
     * copies available. Display-facing (the click preview and the JEI tooltip); the executor itself
     * re-decides per craft from the live pool.
     */
    public static List<ItemStack> protectedItemsUsed(CraftJob job, RecipeManager recipes,
                                                     PlayerInventory inventory) {
        Map<Item, Long> plain = new HashMap<>();
        Map<Item, ItemStack> protectedCopies = new LinkedHashMap<>();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (CraftExecutor.isProtected(stack)) {
                protectedCopies.putIfAbsent(stack.getItem(), copyOfOne(stack));
            } else if (CraftExecutor.usableAsIngredient(stack)) {
                plain.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
            }
        }
        if (protectedCopies.isEmpty()) {
            return ImmutableList.of();
        }
        Map<Item, Long> net = new HashMap<>();
        Set<Item> undetermined = new HashSet<>();
        for (CraftStep step : job.steps()) {
            IRecipe<?> recipe = RecipeIds.byId(recipes, step.recipeId()).orElse(null);
            if (recipe == null) {
                continue; // a vanished recipe fails the job before any of this matters
            }
            if (step.layouts().isEmpty()) {
                // No layout: the executor decides per cell from the alternatives, so any protected
                // item among them may be the one consumed.
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) {
                        continue;
                    }
                    for (Item alternative : slotAlternatives(ingredient)) {
                        if (protectedCopies.containsKey(alternative)) {
                            undetermined.add(alternative);
                        }
                    }
                }
            } else {
                for (SlotLayout<Item> run : step.layouts()) {
                    for (Item item : run.items()) {
                        if (item != null && protectedCopies.containsKey(item)) {
                            net.merge(item, (long) run.crafts(), Long::sum);
                        }
                    }
                }
            }
            ItemStack produced = RecipeIds.resultOf(recipe);
            if (!produced.isEmpty() && protectedCopies.containsKey(produced.getItem())) {
                net.merge(produced.getItem(), -((long) Math.max(1, produced.getCount()) * step.crafts()),
                        Long::sum);
            }
        }
        List<ItemStack> used = new ArrayList<>();
        for (Map.Entry<Item, ItemStack> entry : protectedCopies.entrySet()) {
            if (undetermined.contains(entry.getKey())
                    || net.getOrDefault(entry.getKey(), 0L) > plain.getOrDefault(entry.getKey(), 0L)) {
                used.add(entry.getValue());
            }
        }
        return used;
    }

    private static ItemStack copyOfOne(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    /**
     * All plannable crafting recipes that fit the request grid, indexed by result item, candidate lists
     * sorted by id. Whole-chain gating happens here: a 3×3-only recipe simply does not exist as a
     * producer for a request made from the 2×2 inventory grid.
     */
    private static Map<Item, List<McRecipe>> indexCraftingRecipes(RecipeManager recipes, GridContext grid) {
        Map<Item, List<McRecipe>> byResult = new HashMap<>();
        for (ICraftingRecipe recipe : RecipeIds.craftingRecipes(recipes)) {
            McRecipe info = McRecipe.of(recipe);
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

    /**
     * One solver slot: the ingredient's distinct items in its own order (empty when it resolves to
     * nothing). {@code CraftExecutor} checks the plan's layout items against these lists and builds
     * its {@code SlotAssignment} input with them too, so the executor fills slots from exactly the
     * lists the plan was made with.
     */
    static List<Item> slotAlternatives(Ingredient ingredient) {
        LinkedHashSet<Item> distinct = new LinkedHashSet<>();
        for (ItemStack option : ingredient.getItems()) {
            distinct.add(option.getItem());
        }
        return new ArrayList<>(distinct);
    }

    /** A crafting recipe reduced to the solver's view, or null if it cannot be planned. */
    private static final class McRecipe implements RecipeGraphSolver.RecipeInfo<ResourceLocation, Item> {
        private final ResourceLocation id;
        private final List<List<Item>> ingredientSlots;
        private final Item result;
        private final int resultCount;
        final ItemStack resultStack;
        final boolean fits2x2;
        private final boolean fits3x3;

        private McRecipe(ResourceLocation id, List<List<Item>> ingredientSlots, ItemStack resultStack,
                         boolean fits2x2, boolean fits3x3) {
            this.id = id;
            this.ingredientSlots = ingredientSlots;
            this.result = resultStack.getItem();
            this.resultCount = resultStack.getCount();
            this.resultStack = resultStack;
            this.fits2x2 = fits2x2;
            this.fits3x3 = fits3x3;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public List<List<Item>> ingredientSlots() {
            return ingredientSlots;
        }

        @Override
        public Item result() {
            return result;
        }

        @Override
        public int resultCount() {
            return resultCount;
        }

        boolean fits(GridContext grid) {
            return grid == GridContext.INVENTORY ? fits2x2 : fits3x3;
        }

        static McRecipe of(IRecipe<?> recipe) {
            if (!(recipe instanceof ICraftingRecipe) || recipe.isSpecial()) {
                return null;
            }
            ItemStack resultStack = RecipeIds.resultOf(recipe);
            List<Ingredient> ingredients = recipe.getIngredients();
            if (resultStack.isEmpty() || ingredients.isEmpty()) {
                return null;
            }
            List<List<Item>> slots = new ArrayList<>();
            for (Ingredient ingredient : ingredients) {
                if (ingredient.isEmpty()) {
                    continue; // blank slot in a shaped recipe's grid
                }
                List<Item> alternatives = slotAlternatives(ingredient);
                if (alternatives.isEmpty()) {
                    return null; // unresolvable ingredient (e.g. empty tag)
                }
                slots.add(alternatives);
            }
            if (slots.isEmpty()) {
                return null;
            }
            return new McRecipe(recipe.getId(), slots, resultStack.copy(),
                    recipe.canCraftInDimensions(2, 2), recipe.canCraftInDimensions(3, 3));
        }
    }
}
