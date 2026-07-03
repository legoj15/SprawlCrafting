package com.legoj15.sprawlcrafting.craft.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The Factorio-style planning core: given a target recipe and a snapshot of the player's
 * inventory, produce an ordered list of crafts where every step's ingredients are either
 * already in the inventory or produced by an earlier step. Pure Java — no Minecraft types —
 * so the whole algorithm is unit-testable; {@code CraftPlanner} adapts game types onto it.
 *
 * <p>Authored in Java 8 (plain classes/interfaces, no records or sealed types) because this is
 * the single source of truth compiled into every loader build — including the legacy-Forge
 * 1.12.2 target, whose runtime is Java 8. The public surface (accessor method names, the
 * {@code Result.Success}/{@code Result.Failure} subtypes) is unchanged from the former record
 * form, so the modern callers and the test suite need no edits.
 *
 * <p>Resolution rules (DESIGN.md):
 * <ul>
 *   <li>Stock first: items already (virtually) in the inventory satisfy a slot before any
 *       sub-craft is planned.</li>
 *   <li>Each ingredient slot lists its acceptable alternatives in preference order; the
 *       first alternative in stock wins, then the first alternative that can be crafted. An
 *       alternative satisfiable only by a specific NBT (a lava-filled bucket) is matchable from
 *       stock but not craftable — see {@link RecipeInfo#craftableIngredientSlots()}.</li>
 *   <li>Candidate recipes for a missing item are tried in the order supplied by
 *       {@code producers}; the first whose own tree resolves wins (deterministic).</li>
 *   <li>Cycles (iron ingot ⇄ iron block) are broken by refusing to sub-craft an item that
 *       is already being crafted higher up the same chain; a depth limit backstops it.</li>
 *   <li>Crafting remainders (milk bucket → bucket) become available only when the craft
 *       that consumes the item <em>executes</em> — i.e. after the consuming recipe's own
 *       step — matching real execution order, not at virtual-consume time.</li>
 *   <li>The search is budgeted: pathological recipe graphs (vanilla's 16-color bed cycle
 *       under the wrong inventory, or hostile datapacks) would otherwise explore a
 *       factorial number of branches on the server thread. Exhausting the budget yields
 *       a {@link Result.Failure} with {@code budgetExceeded} set.</li>
 * </ul>
 *
 * @param <R> recipe identifier type
 * @param <K> item identity type
 */
public final class RecipeGraphSolver<R, K> {

    /** Minimal view of a recipe, supplied by the game-side adapter. */
    public interface RecipeInfo<R, K> {
        R id();

        /**
         * Ingredient slots with empty slots already removed. Each slot is a list of the
         * item alternatives that satisfy it (e.g. every plank type), in preference order.
         * Vanilla crafting consumes exactly one item per slot.
         */
        List<List<K>> ingredientSlots();

        /**
         * Per slot (aligned 1:1 with {@link #ingredientSlots()}), the subset of alternatives that
         * can be obtained by CRAFTING one — i.e. a freshly crafted, identity-bare item would
         * actually satisfy the slot. Defaults to the full alternative set, correct whenever item
         * identity fully determines acceptance.
         *
         * <p>An adapter overrides this only when identity is coarser than acceptance. On 1.12.2 a
         * fluid-in-NBT bucket ingredient (Forge/More Buckets, whose lava lives in stack NBT) lists
         * every bucket item as an alternative so a filled one in the inventory matches — but a
         * crafted bucket comes out empty and the ingredient rejects it, so those alternatives are
         * excluded here: usable-if-held, never manufactured. Inventory matching still uses the full
         * {@link #ingredientSlots()}; only the sub-craft decision consults this subset.
         */
        default List<List<K>> craftableIngredientSlots() {
            return ingredientSlots();
        }

        K result();

        int resultCount();
    }

    /** Outcome of a solve: either an executable plan or a best-effort set of missing items. */
    public interface Result<R, K> {

        /** An executable plan: the ordered, merged list of steps to run. */
        final class Success<R, K> implements Result<R, K> {
            private final List<PlannedStep<R>> steps;

            public Success(List<PlannedStep<R>> steps) {
                this.steps = steps;
            }

            public List<PlannedStep<R>> steps() {
                return steps;
            }
        }

        /**
         * @param missing        representative items from failed branches — UX hints, not exhaustive
         * @param budgetExceeded true if the search was aborted because the recipe graph was
         *                       too branchy to prove solvable or unsolvable within budget
         */
        final class Failure<R, K> implements Result<R, K> {
            private final Set<K> missing;
            private final boolean budgetExceeded;

            public Failure(Set<K> missing, boolean budgetExceeded) {
                this.missing = missing;
                this.budgetExceeded = budgetExceeded;
            }

            public Set<K> missing() {
                return missing;
            }

            public boolean budgetExceeded() {
                return budgetExceeded;
            }
        }
    }

    /**
     * Net raw-gatherable demand from a {@link #shortfall} query.
     *
     * @param demands      leaf items (those with no usable producer recipe — the true gatherables)
     *                     mapped to the count the player must acquire, keyed by the representative
     *                     alternative that was chosen
     * @param alternatives per representative item, the full ordered set of items that satisfy the
     *                     slot it was gathered for (so the UI can cycle "coal or charcoal", "any log")
     * @param approximate  true if the depth/attempt budget was hit during the walk, so the map
     *                     may undercount; a UX caveat, not an error
     */
    public static final class ShortfallResult<K> {
        private final Map<K, Integer> demands;
        private final Map<K, List<K>> alternatives;
        private final boolean approximate;

        public ShortfallResult(Map<K, Integer> demands, Map<K, List<K>> alternatives,
                               boolean approximate) {
            this.demands = demands;
            this.alternatives = alternatives;
            this.approximate = approximate;
        }

        public Map<K, Integer> demands() {
            return demands;
        }

        public Map<K, List<K>> alternatives() {
            return alternatives;
        }

        public boolean approximate() {
            return approximate;
        }
    }

    private final Function<K, List<? extends RecipeInfo<R, K>>> producers;
    private final UnaryOperator<K> remainder;
    private final int maxDepth;
    private final int maxAttempts;

    /**
     * @param producers   recipes that produce a given item, in deterministic preference order
     * @param remainder   what consuming one of an item leaves behind (empty bucket), or null
     * @param maxDepth    recursion backstop on the recipe tree depth
     * @param maxAttempts total {@code resolveCraft} invocations allowed per solve before
     *                    aborting — bounds worst-case backtracking on hostile graphs
     */
    public RecipeGraphSolver(Function<K, List<? extends RecipeInfo<R, K>>> producers,
                             UnaryOperator<K> remainder,
                             int maxDepth,
                             int maxAttempts) {
        this.producers = producers;
        this.remainder = remainder;
        this.maxDepth = maxDepth;
        this.maxAttempts = maxAttempts;
    }

    public Result<R, K> solve(RecipeInfo<R, K> target, Map<K, Integer> inventory) {
        Solve solve = new Solve();
        Map<K, Integer> virtual = new HashMap<>(inventory);
        List<PlannedStep<R>> steps = new ArrayList<>();
        if (solve.resolveCraft(target, virtual, steps, new HashSet<>(), 0)) {
            return new Result.Success<>(mergeAdjacent(steps));
        }
        return new Result.Failure<>(solve.missing, solve.attemptsLeft <= 0);
    }

    /** True if the target could be crafted right now, possibly via sub-crafts. */
    public boolean isSolvable(RecipeInfo<R, K> target, Map<K, Integer> inventory) {
        return solve(target, inventory) instanceof Result.Success;
    }

    /**
     * Net raw-gatherable demand to perform one craft of {@code target}: the leaf items — those
     * with no usable producer recipe (ore, logs, smelt-only or trade-only items) — that the player
     * must acquire, with counts, after consuming what's in {@code inventory} and crediting the
     * overproduction of any intermediates the chain would make along the way.
     *
     * <p>This is a best-effort UX estimate, NOT a proof of solvability (the same contract as
     * {@link Result.Failure#missing}). It commits to the first stocked-or-craftable alternative per
     * slot and the first producer per item with no backtracking, so a recipe solvable only via a
     * later producer may be costed along the first producer's path. Crafting remainders are
     * intentionally ignored: a byproduct (an empty bucket) is never a gatherable input, so it can
     * never reduce raw demand.
     */
    public ShortfallResult<K> shortfall(RecipeInfo<R, K> target, Map<K, Integer> inventory) {
        Solve solve = new Solve();
        Map<K, Integer> virtual = new HashMap<>(inventory);
        Map<K, Integer> shortfall = new HashMap<>();
        Map<K, List<K>> alternatives = new HashMap<>();
        for (List<K> slot : target.ingredientSlots()) {
            if (slot.isEmpty()) {
                continue;
            }
            solve.requireSlot(slot, 1, virtual, shortfall, alternatives, new HashSet<>(), 0);
        }
        return new ShortfallResult<>(shortfall, alternatives, solve.approximate);
    }

    /** Mutable per-solve state: the search budget and failure diagnostics. */
    private final class Solve {
        int attemptsLeft = maxAttempts;
        final Set<K> missing = new LinkedHashSet<>();
        /** Set by the shortfall walk when the depth/attempt budget forced an early leaf. */
        boolean approximate = false;

        /**
         * Satisfies every slot of {@code recipe} (consuming from {@code inv}, possibly
         * emitting sub-craft steps), then emits the craft itself and credits its result
         * plus the remainders of everything it consumed. On failure the caller discards
         * {@code inv}/{@code steps}, so partial consumption needs no rollback.
         */
        boolean resolveCraft(RecipeInfo<R, K> recipe,
                             Map<K, Integer> inv,
                             List<PlannedStep<R>> steps,
                             Set<K> crafting,
                             int depth) {
            if (depth > maxDepth || --attemptsLeft < 0) {
                return false;
            }
            List<K> pendingRemainders = new ArrayList<>();
            List<List<K>> slots = recipe.ingredientSlots();
            List<List<K>> craftableSlots = recipe.craftableIngredientSlots();
            for (int s = 0; s < slots.size(); s++) {
                List<K> slot = slots.get(s);
                if (slot.isEmpty()) {
                    continue;
                }
                // Aligned 1:1 by contract; fall back to the full slot if an adapter under-supplies.
                List<K> craftable = s < craftableSlots.size() ? craftableSlots.get(s) : slot;
                if (!satisfySlot(slot, craftable, inv, steps, crafting, depth, pendingRemainders)) {
                    missing.add(slot.get(0));
                    return false;
                }
            }
            steps.add(new PlannedStep<>(recipe.id(), 1));
            inv.merge(recipe.result(), recipe.resultCount(), Integer::sum);
            // Remainders of consumed items physically reappear when this craft executes.
            for (K leftBehind : pendingRemainders) {
                inv.merge(leftBehind, 1, Integer::sum);
            }
            return true;
        }

        private boolean satisfySlot(List<K> alternatives,
                                    List<K> craftableAlternatives,
                                    Map<K, Integer> inv,
                                    List<PlannedStep<R>> steps,
                                    Set<K> crafting,
                                    int depth,
                                    List<K> pendingRemainders) {
            for (K item : alternatives) {
                if (inv.getOrDefault(item, 0) > 0) {
                    consume(item, inv, pendingRemainders);
                    return true;
                }
            }
            // Only craft an alternative whose crafted (bare) form would actually satisfy the slot:
            // a filled-bucket alternative is matchable from stock above but never manufactured here
            // (crafting the bucket yields it empty, which the ingredient rejects).
            for (K item : craftableAlternatives) {
                if (crafting.contains(item) || attemptsLeft <= 0) {
                    continue;
                }
                for (RecipeInfo<R, K> candidate : producers.apply(item)) {
                    // Attempt on snapshots so a failed candidate leaves no trace.
                    Map<K, Integer> invAttempt = new HashMap<>(inv);
                    List<PlannedStep<R>> stepsAttempt = new ArrayList<>(steps);
                    crafting.add(item);
                    boolean ok = resolveCraft(candidate, invAttempt, stepsAttempt, crafting, depth + 1);
                    crafting.remove(item);
                    if (ok && invAttempt.getOrDefault(item, 0) > 0) {
                        inv.clear();
                        inv.putAll(invAttempt);
                        steps.clear();
                        steps.addAll(stepsAttempt);
                        consume(item, inv, pendingRemainders);
                        return true;
                    }
                }
            }
            return false;
        }

        private void consume(K item, Map<K, Integer> inv, List<K> pendingRemainders) {
            int left = inv.merge(item, -1, Integer::sum);
            if (left <= 0) {
                inv.remove(item);
            }
            K leftBehind = remainder.apply(item);
            if (leftBehind != null) {
                pendingRemainders.add(leftBehind);
            }
        }

        // --- Shortfall walk (see RecipeGraphSolver#shortfall) -----------------------------------

        /** Demands {@code qty} of one chosen alternative, remembering the slot's full alternative set. */
        void requireSlot(List<K> alternatives, int qty, Map<K, Integer> virtual, Map<K, Integer> shortfall,
                         Map<K, List<K>> alts, Set<K> crafting, int depth) {
            require(chooseAlternative(alternatives, virtual, crafting), qty, alternatives,
                    virtual, shortfall, alts, crafting, depth);
        }

        /**
         * Picks the alternative to gather for a slot. Unlike {@link #satisfySlot} (which prefers a
         * craftable option), a gather list prefers the simplest thing to obtain: stock you already
         * have, then a raw leaf (an alternative with no recipe — a true gatherable), and only then a
         * craftable alternative. This matters for tag slots like {@code #logs}, which accept both the
         * raw {@code oak_log} and the craftable {@code oak_wood} (4 logs → 3 wood): gathering the log
         * is right; "crafting" the wood block would demand four logs per slot.
         */
        private K chooseAlternative(List<K> alternatives, Map<K, Integer> virtual, Set<K> crafting) {
            for (K item : alternatives) {
                if (virtual.getOrDefault(item, 0) > 0) {
                    return item; // already have it — nothing to gather
                }
            }
            for (K item : alternatives) {
                if (producers.apply(item).isEmpty()) {
                    return item; // a raw gatherable — the simplest answer
                }
            }
            for (K item : alternatives) {
                // Prefer an alternative we can craft entirely from what the player already has, so a
                // slot like #planks resolves to the wood whose logs are in stock instead of demanding
                // the first listed wood (the "I have a log but it says I need one" case).
                if (!crafting.contains(item) && canMakeFromStock(item, virtual, crafting, 0)) {
                    return item;
                }
            }
            for (K item : alternatives) {
                if (!crafting.contains(item)) {
                    return item; // else craft one, skipping anything mid-craft up the chain
                }
            }
            return alternatives.get(0);
        }

        /**
         * Whether {@code item} can be obtained entirely from current (virtual) stock — already held,
         * or craftable through a chain that bottoms out in held items. A choosing heuristic only
         * (ignores quantities; {@link #require} does the real consumption), used to pick the slot
         * alternative that reuses the player's materials. Shares the attempt budget so a hostile graph
         * can't make it loop.
         */
        private boolean canMakeFromStock(K item, Map<K, Integer> virtual, Set<K> crafting, int depth) {
            if (virtual.getOrDefault(item, 0) > 0) {
                return true;
            }
            if (depth > maxDepth || crafting.contains(item) || --attemptsLeft < 0) {
                return false;
            }
            for (RecipeInfo<R, K> producer : producers.apply(item)) {
                crafting.add(item);
                boolean all = true;
                for (List<K> slot : producer.ingredientSlots()) {
                    if (slot.isEmpty()) {
                        continue;
                    }
                    boolean slotOk = false;
                    for (K alt : slot) {
                        if (canMakeFromStock(alt, virtual, crafting, depth + 1)) {
                            slotOk = true;
                            break;
                        }
                    }
                    if (!slotOk) {
                        all = false;
                        break;
                    }
                }
                crafting.remove(item);
                if (all) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Accumulates the leaf demand to obtain {@code qty} of {@code item}: consume stock first,
         * then either sub-craft (recording the sub-recipe's own leaf demands and crediting the
         * overproduction back) or, if the item has no usable producer, record it as a gatherable.
         *
         * <p>The sub-craft is attempted on snapshots so a storage/decompression recipe — where the
         * item's only producer is "uncrafting" a block that is itself made from the item (coal ⟷
         * coal_block) — can be rejected: if producing {@code item} ends up demanding {@code item}
         * again, crafting it was pointless, so it is treated as a raw gatherable for the original
         * {@code qty} (you'd report "1 coal", not the 9 that filling a block would imply).
         */
        void require(K item, int qty, List<K> slotAlternatives, Map<K, Integer> virtual,
                     Map<K, Integer> shortfall, Map<K, List<K>> alts, Set<K> crafting, int depth) {
            if (depth > maxDepth || --attemptsLeft < 0) {
                recordLeaf(item, qty, slotAlternatives, shortfall, alts);
                approximate = true;
                return;
            }
            int fromStock = Math.min(qty, virtual.getOrDefault(item, 0));
            if (fromStock > 0) {
                if (virtual.merge(item, -fromStock, Integer::sum) <= 0) {
                    virtual.remove(item);
                }
                qty -= fromStock;
            }
            if (qty <= 0) {
                return;
            }
            // First producer (deterministic — CraftPlanner sorts by id). A cycle (the item is being
            // crafted higher up) or no producer at all means it bottoms out as a gatherable.
            List<? extends RecipeInfo<R, K>> candidates =
                    crafting.contains(item) ? Collections.<RecipeInfo<R, K>>emptyList() : producers.apply(item);
            if (candidates.isEmpty()) {
                recordLeaf(item, qty, slotAlternatives, shortfall, alts);
                return;
            }
            RecipeInfo<R, K> producer = candidates.get(0);
            int yield = producer.resultCount();
            int crafts = (qty + yield - 1) / yield;
            // Try the sub-craft on copies so a self-referential (decompression) producer can be undone.
            int before = shortfall.getOrDefault(item, 0);
            Map<K, Integer> virtualAttempt = new HashMap<>(virtual);
            Map<K, Integer> shortfallAttempt = new HashMap<>(shortfall);
            Map<K, List<K>> altsAttempt = copyAlternatives(alts);
            crafting.add(item);
            for (List<K> slot : producer.ingredientSlots()) {
                if (slot.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < crafts; i++) {
                    requireSlot(slot, 1, virtualAttempt, shortfallAttempt, altsAttempt, crafting, depth + 1);
                }
            }
            crafting.remove(item);
            if (shortfallAttempt.getOrDefault(item, 0) > before) {
                // Producing the item demanded the item itself — a storage cycle. Gather it raw.
                recordLeaf(item, qty, slotAlternatives, shortfall, alts);
                return;
            }
            virtual.clear();
            virtual.putAll(virtualAttempt);
            shortfall.clear();
            shortfall.putAll(shortfallAttempt);
            alts.clear();
            alts.putAll(altsAttempt);
            int surplus = crafts * yield - qty;
            if (surplus > 0) {
                virtual.merge(item, surplus, Integer::sum);
            }
        }

        /** Records {@code qty} of a gatherable, unioning in the slot's alternatives (for UI cycling). */
        private void recordLeaf(K item, int qty, List<K> slotAlternatives,
                                Map<K, Integer> shortfall, Map<K, List<K>> alts) {
            shortfall.merge(item, qty, Integer::sum);
            List<K> list = alts.computeIfAbsent(item, k -> new ArrayList<>());
            for (K alt : slotAlternatives) {
                if (!list.contains(alt)) {
                    list.add(alt);
                }
            }
        }

        private Map<K, List<K>> copyAlternatives(Map<K, List<K>> alts) {
            Map<K, List<K>> copy = new HashMap<>();
            alts.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
            return copy;
        }
    }

    private List<PlannedStep<R>> mergeAdjacent(List<PlannedStep<R>> steps) {
        List<PlannedStep<R>> merged = new ArrayList<>();
        for (PlannedStep<R> step : steps) {
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).recipeId().equals(step.recipeId())) {
                merged.set(last, merged.get(last).withCrafts(merged.get(last).crafts() + step.crafts()));
            } else {
                merged.add(step);
            }
        }
        return merged;
    }
}
