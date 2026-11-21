package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.legoj15.solver.PlannedStep;
import com.legoj15.solver.RecipeGraphSolver;
import com.legoj15.solver.RecipeGraphSolver.Result;
import com.legoj15.solver.SlotLayout;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
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
    public sealed interface PlanOutcome {
        /** Ready to enqueue. */
        record Planned(CraftJob job) implements PlanOutcome {}

        /** Not a crafting recipe, or a special/dynamic recipe we cannot plan. */
        record Unsupported() implements PlanOutcome {}

        /** The target itself does not fit the grid the request came from (DESIGN.md: whole-chain gating). */
        record NeedsBiggerGrid() implements PlanOutcome {}

        /** Raw resources insufficient; {@code missing} holds representative shortfall items. */
        record Unsolvable(List<Item> missing) implements PlanOutcome {}

        /** The search budget ran out; {@code tooLarge}: makeable, but the plan is longer than one job may be. */
        record TooComplex(boolean tooLarge) implements PlanOutcome {}
    }

    public static PlanOutcome plan(ServerPlayer player, RecipeHolder<?> targetHolder, GridContext grid) {
        return plan(player, targetHolder, grid, Map.of());
    }

    /**
     * Plans against the player's 36 main slots plus {@code station}: the plain-item counts of an
     * open crafting station's storage ({@link StationStorage#counts()}), empty for none.
     */
    public static PlanOutcome plan(ServerPlayer player, RecipeHolder<?> targetHolder, GridContext grid,
                                   Map<Item, Long> station) {
        return plan(player, targetHolder, grid, station, false);
    }

    /**
     * {@link #plan(ServerPlayer, RecipeHolder, GridContext, Map)} with the 36 main slots' protected
     * stacks (enchanted and/or renamed, never damaged; {@link CraftExecutor#isProtected}) counted as
     * material too — the player-confirmed fallback (issue #40) for recipes the plain pool cannot make.
     * The caller that planned this way marks the job {@code CraftJob#setUsesProtected}, which is what
     * lets the executor pick those stacks.
     */
    public static PlanOutcome plan(ServerPlayer player, RecipeHolder<?> targetHolder, GridContext grid,
                                   Map<Item, Long> station, boolean includeProtected) {
        // Cheap reject before building the producer index, so spammed unplannable
        // requests (special/non-crafting/wrong-grid recipes) cost almost nothing.
        if (McRecipe.of(targetHolder, player.registryAccess()) == null) {
            return new PlanOutcome.Unsupported();
        }
        return session(player.level().getServer().getRecipeManager(), player.registryAccess(),
                player.getInventory(), grid, station, includeProtected).plan(targetHolder);
    }

    /**
     * The raw materials the player still needs to make {@code targetHolder} (see
     * {@link Session#shortfall}). The gather list is informational, so callers pass
     * {@link GridContext#CRAFTING_TABLE} to cost the full 3×3 chain regardless of which grid the
     * query came from. The open station's storage counts as material already gathered (issue #33),
     * as in {@link #plan(ServerPlayer, RecipeHolder, GridContext, Map)}. Returns
     * {@link ShortfallView#unavailable()} for unplannable recipes.
     */
    public static ShortfallView shortfall(ServerPlayer player, RecipeHolder<?> targetHolder, GridContext grid) {
        if (McRecipe.of(targetHolder, player.registryAccess()) == null) {
            return ShortfallView.unavailable();
        }
        StationStorage station = StationStorages.open(player);
        return session(player.level().getServer().getRecipeManager(), player.registryAccess(),
                player.getInventory(), grid, station == null ? Map.of() : station.counts()).shortfall(targetHolder);
    }

    /**
     * Snapshots the inventory and indexes all grid-fitting crafting recipes once, so many
     * plan/solvability queries (the client's recipe book pass) share the expensive setup.
     * A session is a point-in-time view: discard it when the inventory or recipes change.
     *
     * <p>The expensive per-grid producer index (which only changes on datapack reload) is
     * cached per {@link RecipeManager}; only the cheap inventory snapshot is per call.
     */
    public static Session session(RecipeManager recipes, RegistryAccess registries,
                                  Inventory inventory, GridContext grid) {
        return session(recipes, registries, inventory, grid, Map.of());
    }

    /**
     * {@link #session(RecipeManager, RegistryAccess, Inventory, GridContext)} with an open crafting
     * station's plain-item counts ({@link StationStorage}) added to the pool; empty for none.
     */
    public static Session session(RecipeManager recipes, RegistryAccess registries,
                                  Inventory inventory, GridContext grid, Map<Item, Long> station) {
        return session(recipes, registries, inventory, grid, station, false);
    }

    /**
     * {@link #session(RecipeManager, RegistryAccess, Inventory, GridContext, Map)} with the 36 main
     * slots' protected stacks counted too ({@code includeProtected}, issue #40). A recipe such a
     * session plans is NOT plain-craftable: the caller must remember the distinction (the client
     * classifies it deferred, the confirming packet is the protected one) and mark the job it hands on.
     */
    public static Session session(RecipeManager recipes, RegistryAccess registries,
                                  Inventory inventory, GridContext grid, Map<Item, Long> station,
                                  boolean includeProtected) {
        return new Session(recipes, registries, grid,
                ProducerIndex.forGrid(recipes, registries, grid),
                snapshotInventory(inventory, station, includeProtected));
    }

    /**
     * Drops the cached producer index for {@code recipes}. Only the 1.21.1 client needs
     * this: a datapack reload mutates its single RecipeManager in place
     * ({@code ClientPacketListener.handleUpdateRecipes} → {@code replaceRecipes}), which
     * the identity-keyed cache cannot see. Servers — and 26.x clients, which never build
     * sessions — replace the instance wholesale, so they self-invalidate.
     */
    public static void invalidateProducerIndex(RecipeManager recipes) {
        ProducerIndex.CACHE.remove(recipes);
    }

    /**
     * Per-RecipeManager, per-grid cache of the producer index. The server replaces its
     * RecipeManager wholesale on datapack reload, so keying on identity makes the cache
     * self-invalidating there; weak keys let stale managers be collected. The 1.21.1
     * client instead mutates its manager in place on reload — its reload hook evicts via
     * {@link CraftPlanner#invalidateProducerIndex}. Keyed by identity so the integrated
     * server's and client's distinct managers don't evict each other in singleplayer.
     */
    private static final class ProducerIndex {
        private static final Map<RecipeManager, Map<GridContext, Map<Item, List<McRecipe>>>> CACHE =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        static Map<Item, List<McRecipe>> forGrid(RecipeManager recipes,
                                                 RegistryAccess registries,
                                                 GridContext grid) {
            Map<GridContext, Map<Item, List<McRecipe>>> byGrid =
                    CACHE.computeIfAbsent(recipes, r -> java.util.Collections.synchronizedMap(
                            new java.util.EnumMap<>(GridContext.class)));
            return byGrid.computeIfAbsent(grid, g -> indexCraftingRecipes(recipes, registries, g));
        }
    }

    public static final class Session {
        private final RecipeManager recipes;
        private final RegistryAccess registries;
        private final GridContext grid;
        private final Map<Item, List<McRecipe>> producers;
        private final Map<Item, Integer> inventory;
        private final RecipeGraphSolver<ResourceLocation, Item> solver;

        private Session(RecipeManager recipes, RegistryAccess registries, GridContext grid,
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

        private final Map<ResourceLocation, Craftability> classifyCache = new HashMap<>();

        /** Cheap repeated query for the recipe book: can this be crafted from raws right now? */
        public boolean isSolvable(RecipeHolder<?> targetHolder) {
            return classify(targetHolder) != Craftability.UNSOLVABLE;
        }

        /**
         * Direct vs deferred vs unsolvable, cached per recipe. Recipe viewers (JEI/REI) use
         * this to decide between letting their builtin transfer the recipe (DIRECT), showing
         * the yellow deferred-craft offer (DEFERRED), or their red error (UNSOLVABLE). A plan
         * with a single step is direct — its one step is the target with all ingredients in
         * stock; any intermediate steps mean deferral.
         */
        public Craftability classify(RecipeHolder<?> targetHolder) {
            return classifyCache.computeIfAbsent(RecipeIds.id(targetHolder), id -> {
                if (!(plan(targetHolder) instanceof PlanOutcome.Planned planned)) {
                    return Craftability.UNSOLVABLE;
                }
                return planned.job().steps().size() == 1 ? Craftability.DIRECT : Craftability.DEFERRED;
            });
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
                // A CraftStep counts crafts in an int: long steps from the solver's quantity path are cut into
                // int-sized ones, and a plan that would need more than the solver's step cap is "too large".
                Result.Success<ResourceLocation, Item> intPlan = success.intSized(RecipeGraphSolver.DEFAULT_MAX_STEPS);
                if (intPlan == null) {
                    return new PlanOutcome.TooComplex(true);
                }
                List<PlannedStep<ResourceLocation>> planned = intPlan.steps();
                List<CraftStep> steps = new ArrayList<>(planned.size());
                for (int i = 0; i < planned.size(); i++) {
                    PlannedStep<ResourceLocation> step = planned.get(i);
                    steps.add(new CraftStep(step.recipeId(), (int) step.crafts(),
                            !fits2x2(step.recipeId(), target), intPlan.layouts().get(i)));
                }
                return new PlanOutcome.Planned(new CraftJob(RecipeIds.id(targetHolder), target.resultStack(), steps));
            }
            Result.Failure<ResourceLocation, Item> failure = (Result.Failure<ResourceLocation, Item>) result;
            if (failure.budgetExceeded()) {
                return new PlanOutcome.TooComplex(failure.tooLarge());
            }
            return new PlanOutcome.Unsolvable(failure.missing().stream().toList());
        }

        /**
         * The net raw-gatherable demand to make {@code targetHolder} once, mapping the solver's
         * leaf {@link Item}s back to registry ids (sorted for a deterministic wire order). Used both
         * for the chat-free "gather list" UI and the server reply on 26.x. A recipe that doesn't fit
         * the grid yields an empty demand list (the screen still titles itself from the target).
         */
        public ShortfallView shortfall(RecipeHolder<?> targetHolder) {
            McRecipe target = McRecipe.of(targetHolder, registries);
            if (target == null) {
                return ShortfallView.unavailable();
            }
            ResourceLocation targetId = BuiltInRegistries.ITEM.getKey(target.result());
            if (!target.fits(grid)) {
                return new ShortfallView(targetId, target.resultCount(), false, false, List.of());
            }
            RecipeGraphSolver.ShortfallResult<Item> result = solver.shortfall(target, inventory);
            List<ItemDemand> demands = new ArrayList<>();
            // demandsLong, not the int-clamped demands(): batched gather lists exceed Integer.MAX_VALUE.
            result.demandsLong().forEach((item, count) -> {
                List<Item> altItems = result.alternatives().getOrDefault(item, List.of(item));
                List<ResourceLocation> altIds = altItems.stream()
                        .map(BuiltInRegistries.ITEM::getKey).toList();
                demands.add(new ItemDemand(altIds, count));
            });
            demands.sort(Comparator.comparing(d -> d.items().get(0).toString()));
            return new ShortfallView(targetId, target.resultCount(), result.approximate(), result.saturated(), demands);
        }

        private boolean fits2x2(ResourceLocation recipeId, McRecipe target) {
            if (recipeId.equals(target.id())) {
                return target.fits2x2();
            }
            return RecipeIds.byId(recipes, recipeId)
                    .map(h -> h.value() instanceof CraftingRecipe r && canCraftInDimensions(r, 2, 2))
                    .orElse(true);
        }
    }

    /**
     * The 36 main inventory slots as an item-count multiset. Armor and offhand are
     * deliberately excluded: planning must never consume worn equipment, and execution
     * only pulls from the main slots (see {@code CraftExecutor}). Damaged stacks are always skipped;
     * enchanted/renamed (protected, issue #40) stacks are skipped too unless {@code includeProtected}
     * — the player-confirmed tier, mirroring exactly what the executor may pick for a job planned
     * with it ({@link CraftExecutor#usableAsIngredient(ItemStack, boolean)}).
     *
     * <p>{@code station} adds an open crafting station's plain-item counts (already usable, see
     * {@link StationStorage}); the solver counts in {@code int}, so each total is clamped at
     * {@link Integer#MAX_VALUE} (an ME network can hold far more).
     */
    private static Map<Item, Integer> snapshotInventory(Inventory inventory, Map<Item, Long> station,
                                                        boolean includeProtected) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && CraftExecutor.usableAsIngredient(stack, includeProtected)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        station.forEach((item, count) -> {
            if (count > 0) {
                counts.merge(item, clampToInt(count), CraftPlanner::saturatingIntSum);
            }
        });
        return counts;
    }

    /**
     * Whether any of the 36 main slots holds a protected stack (enchanted and/or renamed, never
     * damaged; {@link CraftExecutor#isProtected}). A cheap gate so callers skip the second,
     * protected planning pass (issue #40) when there is nothing for it to add.
     */
    public static boolean hasProtected(Inventory inventory) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
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
     * copies available. {@code station} adds the plain counts of the other sources the plan used
     * (an open crafting station's storage; empty for none): the executor draws those before any
     * protected copy, so without them an item the station supplies anyway would be over-warned.
     * Display-facing (the click preview and the JEI tooltip); the executor itself
     * re-decides per craft from the live pool.
     */
    public static List<ItemStack> protectedItemsUsed(CraftJob job, RecipeManager recipes,
                                                     RegistryAccess registries, Inventory inventory,
                                                     Map<Item, Long> station) {
        Map<Item, Long> plain = new HashMap<>();
        Map<Item, ItemStack> protectedCopies = new LinkedHashMap<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
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
            return List.of();
        }
        station.forEach((item, count) -> {
            if (count > 0) {
                plain.merge(item, count, Long::sum);
            }
        });
        Map<Item, Long> net = new HashMap<>();
        Set<Item> undetermined = new HashSet<>();
        for (CraftStep step : job.steps()) {
            RecipeHolder<?> holder = RecipeIds.byId(recipes, step.recipeId()).orElse(null);
            if (holder == null) {
                continue; // a vanished recipe fails the job before any of this matters
            }
            //? if >=1.21.11 {
            /*java.util.List<Ingredient> ingredients = holder.value() instanceof CraftingRecipe recipe
                    ? recipe.placementInfo().ingredients() : java.util.List.of();*/
            //?} else {
            List<Ingredient> ingredients = holder.value() instanceof CraftingRecipe recipe
                    ? recipe.getIngredients() : List.of();
            //?}
            if (step.layouts().isEmpty()) {
                // No layout: the executor decides per cell from the alternatives, so any protected
                // item among them may be the one consumed.
                for (Ingredient ingredient : ingredients) {
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
            ItemStack produced = RecipeIds.resultOf(holder.value(), registries);
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

    private static int clampToInt(long count) {
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    private static int saturatingIntSum(int a, int b) {
        return clampToInt((long) a + b);
    }

    /**
     * All plannable crafting recipes that fit the request grid, indexed by result item,
     * candidate lists sorted by id. Whole-chain gating happens here: a 3×3-only recipe
     * simply does not exist as a producer for a request made from the 2×2 inventory grid.
     */
    private static Map<Item, List<McRecipe>> indexCraftingRecipes(RecipeManager recipes,
                                                                  RegistryAccess registries,
                                                                  GridContext grid) {
        Map<Item, List<McRecipe>> byResult = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder : RecipeIds.craftingRecipes(recipes)) {
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
        //? if >=1.21.11 {
        /*net.minecraft.world.item.ItemStackTemplate remainder = item.getCraftingRemainder();
        return remainder == null ? null : remainder.item().value();*/
        //?} else {
        return item.hasCraftingRemainingItem() ? item.getCraftingRemainingItem() : null;
        //?}
    }

    /**
     * Whether {@code recipe} fits a w×h grid. 26.x removed {@code canCraftInDimensions}; reconstruct
     * the vanilla rule — shaped by declared width/height, anything else by ingredient count.
     */
    private static boolean canCraftInDimensions(CraftingRecipe recipe, int w, int h) {
        //? if >=1.21.11 {
        /*if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            return shaped.getWidth() <= w && shaped.getHeight() <= h;
        }
        return recipe.placementInfo().ingredients().size() <= w * h;*/
        //?} else {
        return recipe.canCraftInDimensions(w, h);
        //?}
    }

    /**
     * One solver slot: the ingredient's distinct items in its own order (empty when it resolves to
     * nothing). {@code CraftExecutor} checks the plan's layout items against these lists and builds
     * its {@code SlotAssignment} input with them too, so the executor fills slots from exactly the
     * lists the plan was made with.
     */
    static List<Item> slotAlternatives(Ingredient ingredient) {
        //? if >=1.21.11 {
        /*return ingredient.items().map(net.minecraft.core.Holder::value).distinct().toList();*/
        //?} else {
        return Arrays.stream(ingredient.getItems())
                .map(ItemStack::getItem)
                .distinct()
                .toList();
        //?}
    }

    /** A crafting recipe reduced to the solver's view, or null if it cannot be planned. */
    private record McRecipe(ResourceLocation id, List<List<Item>> ingredientSlots,
                            Item result, int resultCount, ItemStack resultStack,
                            boolean fits2x2, boolean fits3x3)
            implements RecipeGraphSolver.RecipeInfo<ResourceLocation, Item> {

        boolean fits(GridContext grid) {
            return grid == GridContext.INVENTORY ? fits2x2 : fits3x3;
        }

        static McRecipe of(RecipeHolder<?> holder, RegistryAccess registries) {
            if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
                return null;
            }
            ItemStack resultStack = RecipeIds.resultOf(recipe, registries);
            //? if >=1.21.11 {
            /*java.util.List<Ingredient> ingredients = recipe.placementInfo().ingredients();*/
            //?} else {
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            //?}
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
            return new McRecipe(RecipeIds.id(holder), slots, resultStack.getItem(), resultStack.getCount(),
                    resultStack, canCraftInDimensions(recipe, 2, 2), canCraftInDimensions(recipe, 3, 3));
        }
    }
}
