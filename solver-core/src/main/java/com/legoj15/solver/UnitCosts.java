package com.legoj15.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The unit costs a {@link CostModel} induces on a recipe graph, derived lazily and kept for the life of
 * the solver that owns them (see {@link RecipeGraphSolver#unitCost}).
 *
 * <p>The definition, with {@code D = maxDepth}:
 * <ul>
 *   <li>{@code cost_0(i) = gatherCost(i)}</li>
 *   <li>{@code cost_r(i) = min(gatherCost(i), min over producers p of i of recipe_{r-1}(p))}</li>
 *   <li>{@code recipe_{r-1}(p) = ceil((recipeCost(p) + sum over slots of quantity x min over the slot's
 *       alternatives of cost_{r-1}(alt) + cost_{r-1}(station), when the recipe has one) / resultCount)},
 *       saturating at {@link CostModel#UNOBTAINABLE}</li>
 *   <li>{@code unitCost(i) = cost_D(i)}, which is {@code cost_0(i)} for {@code D <= 0}: no recipe may
 *       run at all (a negative {@code maxDepth} is tolerated, as planning tolerates it)</li>
 * </ul>
 * An alternative that cannot be crafted in its slot (see
 * {@link RecipeGraphSolver.RecipeInfo#craftableIngredientSlots()}) is worth its gather cost at every
 * depth; the station is counted once per craft, in full. So no derivation uses more than {@code D}
 * recipes in a row (what lies deeper is unobtainable) and a cycle is not a way to get something for
 * nothing: ingot, block and ingot again can only ever cost more than the ingot it started from, and a
 * cycle with no way in from outside stays unobtainable.
 *
 * <p>{@code unitCost(i)} is a pure function of the item, the producers, the cost model and
 * {@code maxDepth}: the same in any query order. That is why every derived item keeps its whole
 * history {@code cost_0 .. cost_D} rather than only its final value: a later query that reaches it
 * with {@code r} levels left must read {@code cost_r}, never the {@code cost_D} it was priced at from
 * its own root (an item four recipes from anything gatherable is unobtainable at depth 3 whether or
 * not one of its ingredients was asked about first). Derivation relaxes a whole connected piece of
 * the graph at once, one level per round, reading earlier pieces' histories at the level being
 * computed, so a dense cycle (sixteen bed colours, each re-dyeable from any other) costs time linear
 * in depth times edges, not factorial. A history stops at the round after which nothing changes; its
 * last value stands for every deeper level.
 *
 * <p>Memory: the kept history is one value per item per relaxation round, and the rounds stop when a
 * round changes nothing, so it is bounded by the item count times the depth of the deepest useful chain
 * (the longest run of recipes that still lowers some cost), not by {@code maxDepth}; a derivation's
 * working levels have the same bound. Real recipe graphs are shallow. A synthetic single chain of 4000
 * recipes priced at {@code maxDepth} 4096 is the bad case: every round changes one more item, so a
 * reviewer measured about 427 MB. Keeping only the rounds at which an item's value changes would cut the
 * kept part, not the working levels, and is not done.
 *
 * <p>Costs ignore stock, so what is derived here is stable and shareable; every entry point is
 * synchronized because a solver may be asked from several threads.
 */
final class UnitCosts<R, K> {

    private final CostModel<R, K> model;
    private final Function<K, List<? extends RecipeGraphSolver.RecipeInfo<R, K>>> producers;
    private final int maxDepth;

    private final Map<K, Long> gathered = new HashMap<>();
    private final Map<K, Derived<R, K>> derived = new HashMap<>();

    UnitCosts(CostModel<R, K> model,
              Function<K, List<? extends RecipeGraphSolver.RecipeInfo<R, K>>> producers,
              int maxDepth) {
        this.model = model;
        this.producers = producers;
        this.maxDepth = maxDepth;
    }

    /** What is known about one item once its piece of the graph has been priced. */
    private static final class Derived<R, K> {
        /** {@code cost_r} at index {@code r}; the last entry holds for every deeper level. */
        final long[] history;
        /** Gathering is obtainable and no dearer than the cheapest producer: gather what stock lacks. */
        final boolean leaf;
        /** Every producer, cheapest per unit first, unobtainable ones last, ties in the caller's order. */
        final List<RecipeGraphSolver.RecipeInfo<R, K>> byCost;
        /** How many leading entries of {@link #byCost} have a finite cost. */
        final int obtainable;

        Derived(long[] history, boolean leaf, List<RecipeGraphSolver.RecipeInfo<R, K>> byCost, int obtainable) {
            this.history = history;
            this.leaf = leaf;
            this.byCost = byCost;
            this.obtainable = obtainable;
        }

        /** {@code cost_depth}; a depth below 0 (a solver built with a negative maxDepth) reads {@code cost_0}. */
        long at(int depth) {
            return history[Math.max(0, Math.min(depth, history.length - 1))];
        }
    }

    /** A recipe as costing reads it: slots after quantities, its station, its own cost and yield. */
    private static final class Shape<K> {
        final List<List<K>> slots;
        final List<List<K>> craftable;
        final K station;
        final long recipeCost;
        final int yield;

        Shape(List<List<K>> slots, List<List<K>> craftable, K station, long recipeCost, int yield) {
            this.slots = slots;
            this.craftable = craftable;
            this.station = station;
            this.recipeCost = recipeCost;
            this.yield = yield;
        }
    }

    /** An item while its piece of the graph is being priced. */
    private static final class Node<R, K> {
        final int index;
        final long gather;
        final List<RecipeGraphSolver.RecipeInfo<R, K>> producers = new ArrayList<>();

        Node(int index, long gather) {
            this.index = index;
            this.gather = gather;
        }
    }

    private static final class Priced<R, K> {
        final long cost;
        final RecipeGraphSolver.RecipeInfo<R, K> recipe;

        Priced(long cost, RecipeGraphSolver.RecipeInfo<R, K> recipe) {
            this.cost = cost;
            this.recipe = recipe;
        }
    }

    /** The unit cost of {@code item}: gathering it or the cheapest craft, or UNOBTAINABLE. */
    synchronized long unit(K item) {
        return entry(item).at(maxDepth);
    }

    /** Whether what stock cannot cover of the item is to be gathered rather than crafted. */
    synchronized boolean isLeaf(K item) {
        return entry(item).leaf;
    }

    /**
     * Every producer of the item in the order a plan tries them: cheapest per unit first, unobtainable
     * ones last (still tried: stock may hold what they need), ties in the caller's order.
     */
    synchronized List<RecipeGraphSolver.RecipeInfo<R, K>> producersByCost(K item) {
        return entry(item).byCost;
    }

    /**
     * The producers a shortfall descends into: the obtainable ones, cheapest first; or, when none is
     * obtainable, every producer in the caller's order (all cost the same then).
     */
    synchronized List<RecipeGraphSolver.RecipeInfo<R, K>> obtainableProducers(K item) {
        Derived<R, K> known = entry(item);
        return known.obtainable == 0 ? known.byCost : known.byCost.subList(0, known.obtainable);
    }

    /**
     * What {@code item} is worth in a slot: its unit cost where the slot can be filled by crafting it,
     * its gather cost where it can only be matched from stock.
     */
    synchronized long slotCost(K item, boolean craftable) {
        return craftable ? entry(item).at(maxDepth) : gather(item);
    }

    private Derived<R, K> entry(K item) {
        Derived<R, K> known = derived.get(item);
        if (known == null) {
            derive(item);
            known = derived.get(item);
        }
        return known;
    }

    private long gather(K item) {
        Long known = gathered.get(item);
        if (known == null) {
            long cost = model.gatherCost(item);
            if (cost < 0) {
                throw new IllegalArgumentException("CostModel.gatherCost must not be negative: " + cost
                        + " for " + item);
            }
            known = cost;
            gathered.put(item, known);
        }
        return known;
    }

    private Shape<K> shape(RecipeGraphSolver.RecipeInfo<R, K> recipe) {
        long cost = model.recipeCost(recipe);
        if (cost < 0) {
            throw new IllegalArgumentException("CostModel.recipeCost must not be negative: " + cost
                    + " for recipe " + recipe.id());
        }
        return new Shape<>(PlanSimulator.expand(recipe, recipe.ingredientSlots()),
                PlanSimulator.expand(recipe, recipe.craftableIngredientSlots()),
                recipe.station(), cost, recipe.resultCount());
    }

    /**
     * Prices {@code root} and everything it can be made through that is not priced yet. Items priced
     * by an earlier query are read from their histories at the level each round needs, so they enter
     * the new piece exactly as they would had they been priced with it.
     */
    private void derive(K root) {
        Map<K, Node<R, K>> piece = new LinkedHashMap<>();
        // Shapes live for this derivation only: a consumer whose producers function builds new recipe
        // objects on every call must not grow a cache without bound.
        Map<RecipeGraphSolver.RecipeInfo<R, K>, Shape<K>> shapes = new IdentityHashMap<>();
        // The deepest level at which an earlier piece this one reads still changes: until the rounds pass
        // it, an unchanged round does not mean the next one will be unchanged too.
        int settledOutside = 0;
        ArrayDeque<K> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            K item = pending.pop();
            if (piece.containsKey(item)) {
                continue;
            }
            Derived<R, K> earlier = derived.get(item);
            if (earlier != null) {
                settledOutside = Math.max(settledOutside, earlier.history.length - 1);
                continue;
            }
            Node<R, K> node = new Node<>(piece.size(), gather(item));
            piece.put(item, node);
            if (maxDepth < 1) {
                continue; // no recipe may be used at all: only gathering remains
            }
            for (RecipeGraphSolver.RecipeInfo<R, K> candidate : producers.apply(item)) {
                if (candidate.resultCount() <= 0 || !Objects.equals(candidate.result(), item)) {
                    continue;
                }
                node.producers.add(candidate);
                Shape<K> shape = shape(candidate);
                shapes.put(candidate, shape);
                for (List<K> slot : shape.craftable) {
                    for (K alternative : slot) {
                        pending.push(alternative);
                    }
                }
                if (shape.station != null) {
                    pending.push(shape.station);
                }
            }
        }
        // levels.get(r)[node.index] = cost_r of that item: gathering only at r = 0, one more recipe in a
        // row per round. Once a round changes nothing and the earlier pieces are settled at the level it
        // read, every deeper round would repeat it, so the histories stop there.
        List<long[]> levels = new ArrayList<>();
        long[] level = new long[piece.size()];
        for (Node<R, K> node : piece.values()) {
            level[node.index] = node.gather;
        }
        levels.add(level);
        for (int r = 1; r <= maxDepth; r++) {
            long[] previous = level;
            level = previous.clone();
            boolean changed = false;
            for (Node<R, K> node : piece.values()) {
                long best = node.gather;
                for (RecipeGraphSolver.RecipeInfo<R, K> producer : node.producers) {
                    best = Math.min(best, perUnit(shapes.get(producer), r - 1, piece, levels));
                }
                if (best != previous[node.index]) {
                    level[node.index] = best;
                    changed = true;
                }
            }
            if (!changed && r - 1 >= settledOutside) {
                break;
            }
            levels.add(level);
        }
        for (Map.Entry<K, Node<R, K>> entry : piece.entrySet()) {
            Node<R, K> node = entry.getValue();
            List<Priced<R, K>> priced = new ArrayList<>(node.producers.size());
            int obtainable = 0;
            for (RecipeGraphSolver.RecipeInfo<R, K> producer : node.producers) {
                // A producer's own cost is recipe_{D-1}: what it adds to cost_D of its result.
                long cost = perUnit(shapes.get(producer), maxDepth - 1, piece, levels);
                priced.add(new Priced<>(cost, producer));
                if (cost != CostModel.UNOBTAINABLE) {
                    obtainable++;
                }
            }
            // Collections.sort is stable: equal costs keep the order the caller listed the producers in,
            // and UNOBTAINABLE (Long.MAX_VALUE) sorts last.
            Collections.sort(priced, (a, b) -> Long.compare(a.cost, b.cost));
            List<RecipeGraphSolver.RecipeInfo<R, K>> byCost = new ArrayList<>(priced.size());
            for (Priced<R, K> p : priced) {
                byCost.add(p.recipe);
            }
            long cheapest = priced.isEmpty() ? CostModel.UNOBTAINABLE : priced.get(0).cost;
            boolean leaf = node.gather != CostModel.UNOBTAINABLE && node.gather <= cheapest;
            long[] history = new long[levels.size()];
            for (int r = 0; r < history.length; r++) {
                history[r] = levels.get(r)[node.index];
            }
            derived.put(entry.getKey(), new Derived<>(trim(history), leaf,
                    Collections.unmodifiableList(byCost), obtainable));
        }
    }

    /** {@code history} without the repeats of its last value (the last entry stands for all deeper levels). */
    private static long[] trim(long[] history) {
        int length = history.length;
        while (length > 1 && history[length - 2] == history[length - 1]) {
            length--;
        }
        return length == history.length ? history : Arrays.copyOf(history, length);
    }

    /**
     * {@code recipe_depth} of {@code shape}: one unit's cost through it, its ingredients and station read
     * at {@code cost_depth}; see the class doc. Below depth 0 no recipe may run at all.
     */
    private long perUnit(Shape<K> shape, int depth, Map<K, Node<R, K>> piece, List<long[]> levels) {
        if (depth < 0) {
            return CostModel.UNOBTAINABLE;
        }
        long sum = shape.recipeCost;
        List<K> previousSlot = null;
        List<K> previousCraftable = null;
        long best = 0;
        for (int i = 0; i < shape.slots.size() && sum != CostModel.UNOBTAINABLE; i++) {
            List<K> slot = shape.slots.get(i);
            List<K> craftable = shape.craftable.get(i);
            if (slot != previousSlot || craftable != previousCraftable) {
                // The copies of a slot with a quantity are one list repeated: price it once.
                best = CostModel.UNOBTAINABLE;
                for (K alternative : slot) {
                    best = Math.min(best, craftable.contains(alternative)
                            ? cost(alternative, depth, piece, levels) : gather(alternative));
                }
                previousSlot = slot;
                previousCraftable = craftable;
            }
            sum = RecipeGraphSolver.satAdd(sum, best);
        }
        if (shape.station != null) {
            sum = RecipeGraphSolver.satAdd(sum, cost(shape.station, depth, piece, levels));
        }
        return sum == CostModel.UNOBTAINABLE ? CostModel.UNOBTAINABLE : RecipeGraphSolver.ceilDiv(sum, shape.yield);
    }

    /** {@code cost_depth(item)}, from the piece being priced or from an earlier piece's history. */
    private long cost(K item, int depth, Map<K, Node<R, K>> piece, List<long[]> levels) {
        Node<R, K> node = piece.get(item);
        if (node != null) {
            return levels.get(Math.min(depth, levels.size() - 1))[node.index];
        }
        return derived.get(item).at(depth);
    }
}
