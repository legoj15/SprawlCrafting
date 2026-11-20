package com.legoj15.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Replays a plan the way the executors run it: one craft at a time, every ingredient slot filled by
 * {@link SlotAssignment} against the stock as it stands at that craft, the result and the remainders
 * of what was consumed credited once the craft is done. A plan passes only if no slot of any craft
 * comes up empty. {@link RecipeGraphSolver#solve} returns no plan this rejects.
 *
 * <p>Cost is O(steps x slots), not O(crafts): {@link SlotAssignment}'s choice for a craft depends on
 * each alternative's stock only up to the number of slots that list it, so a run of crafts during
 * which no such capped count changes repeats one assignment and is applied in one go.
 */
public final class PlanSimulator {

    private PlanSimulator() {
    }

    /** Outcome of {@link #replay}. */
    public static final class Replay<R, K> {
        private final int failedStep;
        private final R failedRecipe;
        private final K missing;

        Replay(int failedStep, R failedRecipe, K missing) {
            this.failedStep = failedStep;
            this.failedRecipe = failedRecipe;
            this.missing = missing;
        }

        /** True when every craft of every step found stock for every slot. */
        public boolean ok() {
            return failedStep < 0;
        }

        /** Index into the plan of the first step that came up short, or -1. */
        public int failedStep() {
            return failedStep;
        }

        /** That step's recipe, or null. */
        public R failedRecipe() {
            return failedRecipe;
        }

        /**
         * The first alternative of the first slot that could not be filled, or null (also null when
         * the step's recipe could not be looked up).
         */
        public K missing() {
            return missing;
        }

        @Override
        public String toString() {
            return ok() ? "Replay[ok]"
                    : "Replay[failedStep=" + failedStep + ", recipe=" + failedRecipe + ", missing=" + missing + "]";
        }
    }

    /**
     * @param plan      the steps to replay, in order
     * @param recipes   recipe for a step's id (null fails that step)
     * @param remainder what consuming one of an item leaves behind, or null; the function itself may
     *                  be null for "nothing ever"
     * @param stock     the starting stock (counts below 1 are ignored)
     */
    public static <R, K> Replay<R, K> replay(List<PlannedStep<R>> plan,
                                             Function<? super R, ? extends RecipeGraphSolver.RecipeInfo<R, K>> recipes,
                                             UnaryOperator<K> remainder,
                                             Map<K, Integer> stock) {
        Map<K, Long> start = new HashMap<>();
        for (Map.Entry<K, Integer> entry : stock.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                start.put(entry.getKey(), RecipeGraphSolver.stockCount(entry.getValue()));
            }
        }
        UndoMap<K, Long> inv = new UndoMap<>(start);
        for (int i = 0; i < plan.size(); i++) {
            PlannedStep<R> step = plan.get(i);
            RecipeGraphSolver.RecipeInfo<R, K> recipe = recipes.apply(step.recipeId());
            if (recipe == null) {
                return new Replay<>(i, step.recipeId(), null);
            }
            Batch<K> batch = run(recipe, step.crafts(), inv, remainder, true);
            if (batch.isShort()) {
                return new Replay<>(i, step.recipeId(), batch.firstMissing);
            }
        }
        return new Replay<>(-1, null, null);
    }

    /** What {@link #run} did to one step. */
    static final class Batch<K> {
        /** The recipe's non-empty slots, aligned with {@link #shortage}. */
        final List<List<K>> slots;
        /** Per slot, the crafts in which it found no stock. */
        final long[] shortage;
        /** Crafts completed before the first short one (all of them when none was short). */
        long completeBeforeShort;
        K firstMissing;
        boolean anyShort;

        Batch(List<List<K>> slots) {
            this.slots = slots;
            this.shortage = new long[slots.size()];
        }

        boolean isShort() {
            return anyShort;
        }
    }

    /** The recipe's slots with empty ones (blank cells) removed. */
    static <R, K> List<List<K>> nonEmptySlots(RecipeGraphSolver.RecipeInfo<R, K> recipe) {
        List<List<K>> slots = new ArrayList<>();
        for (List<K> slot : recipe.ingredientSlots()) {
            if (!slot.isEmpty()) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * Runs {@code crafts} crafts of {@code recipe} against {@code inv}, mutating it. A short craft
     * consumes what it could assign and credits nothing; with {@code stopAtShort} the run ends at the
     * first one instead (before mutating anything for it).
     */
    static <R, K> Batch<K> run(RecipeGraphSolver.RecipeInfo<R, K> recipe, long crafts, UndoMap<K, Long> inv,
                               UnaryOperator<K> remainder, boolean stopAtShort) {
        List<List<K>> slots = nonEmptySlots(recipe);
        Batch<K> batch = new Batch<>(slots);
        // How many slots list each alternative: the choice reads no count beyond that.
        Map<K, Integer> listing = new LinkedHashMap<>();
        for (List<K> slot : slots) {
            for (int a = 0; a < slot.size(); a++) {
                K item = slot.get(a);
                if (slot.indexOf(item) == a) {
                    listing.merge(item, 1, Integer::sum);
                }
            }
        }
        K result = recipe.result();
        long yield = Math.max(0, recipe.resultCount());
        long left = crafts;
        while (left > 0) {
            Object[] chosen = SlotAssignment.choose(slots, item -> count(inv, item));
            Map<K, Long> use = new LinkedHashMap<>();
            boolean isShort = false;
            for (int s = 0; s < chosen.length; s++) {
                if (chosen[s] == null) {
                    isShort = true;
                    if (batch.firstMissing == null) {
                        batch.firstMissing = slots.get(s).get(0);
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    K item = (K) chosen[s];
                    use.merge(item, 1L, Long::sum);
                }
            }
            if (isShort && !batch.anyShort) {
                batch.anyShort = true;
                if (stopAtShort) {
                    return batch;
                }
            }
            Map<K, Long> made = new LinkedHashMap<>();
            if (!isShort) {
                if (yield > 0 && result != null) {
                    made.merge(result, yield, Long::sum);
                }
                if (remainder != null) {
                    for (Map.Entry<K, Long> entry : use.entrySet()) {
                        K leftBehind = remainder.apply(entry.getKey());
                        if (leftBehind != null) {
                            made.merge(leftBehind, entry.getValue(), Long::sum);
                        }
                    }
                }
            }
            // Repeat this assignment while no listed alternative's capped count moves.
            long repeat = left;
            for (Map.Entry<K, Integer> entry : listing.entrySet()) {
                K item = entry.getKey();
                long cap = entry.getValue();
                long drop = use.getOrDefault(item, 0L) - made.getOrDefault(item, 0L);
                if (drop == 0) {
                    continue;
                }
                long have = count(inv, item);
                if (have < cap) {
                    repeat = 1;
                    break;
                }
                if (drop > 0) {
                    repeat = Math.min(repeat, (have - cap) / drop + 1);
                }
            }
            repeat = Math.max(1, repeat);
            // Net each item's change per craft before applying it: a craft can consume an item it is
            // also credited (its own result in a slot, a remainder equal to an alternative), and
            // booking the two separately would clamp the consumption at zero and inflate the stock.
            Map<K, Long> net = new LinkedHashMap<>();
            for (Map.Entry<K, Long> entry : use.entrySet()) {
                net.merge(entry.getKey(), -entry.getValue(), Long::sum);
            }
            for (Map.Entry<K, Long> entry : made.entrySet()) {
                net.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            for (Map.Entry<K, Long> entry : net.entrySet()) {
                long delta = entry.getValue();
                if (delta == 0) {
                    continue;
                }
                long have = count(inv, entry.getKey());
                long after = delta > 0
                        ? RecipeGraphSolver.satAdd(have, RecipeGraphSolver.satMul(repeat, delta))
                        : have - RecipeGraphSolver.satMul(repeat, -delta);
                if (after <= 0) {
                    inv.remove(entry.getKey());
                } else {
                    inv.put(entry.getKey(), after);
                }
            }
            if (isShort) {
                for (int s = 0; s < chosen.length; s++) {
                    if (chosen[s] == null) {
                        batch.shortage[s] = RecipeGraphSolver.satAdd(batch.shortage[s], repeat);
                    }
                }
            } else if (!batch.anyShort) {
                batch.completeBeforeShort += repeat;
            }
            left -= repeat;
        }
        return batch;
    }

    private static <K> long count(UndoMap<K, Long> inv, K item) {
        Long have = inv.get(item);
        return have == null ? 0 : have;
    }
}
