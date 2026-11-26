package com.legoj15.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Replays a plan the way an executor runs it without a layout: one craft at a time, every ingredient slot filled by
 * {@link SlotAssignment} against the stock as it stands at that craft, the result and the remainders
 * of what was consumed credited once the craft is done. A plan passes only if no slot of any craft
 * comes up empty.
 *
 * <p>A recipe's {@link RecipeGraphSolver.RecipeInfo#station() station} must be in stock at the step and
 * is not consumed: it is held out of the stock while the step's crafts run (so a recipe that also
 * lists it as an ingredient needs one more) and put back afterwards. A slot with a quantity above 1
 * is that many slots, see {@link RecipeGraphSolver.RecipeInfo#ingredientSlotCount}.
 *
 * <p>The chunking is bounded by the number of times a capped count changes, not by the crafts:
 * {@link SlotAssignment}'s choice for a craft depends on each alternative's stock only up to the
 * number of slots that list it, so a run of crafts during which no such capped count moves repeats
 * one assignment and is applied in one go. Without remainders that refill a listed alternative that
 * is O(steps x slots) chunks; WITH them the capped counts can flip-flop every craft (X leaves Y, Y
 * leaves X) and the chunking can degrade to one chunk per craft.
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
         * The first alternative of the first slot that could not be filled, or the step's station
         * when none was in stock, or null (also null when the step's recipe could not be looked up).
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
            K station = recipe.station();
            if (station != null && !RecipeGraphSolver.hold(inv, station)) {
                return new Replay<>(i, step.recipeId(), station);
            }
            Batch<K> batch = run(recipe, step.crafts(), inv, remainder, true);
            if (batch.isShort()) {
                return new Replay<>(i, step.recipeId(), batch.firstMissing);
            }
            if (station != null) {
                RecipeGraphSolver.release(inv, station);
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

    /**
     * The recipe's slots with empty ones (blank cells) removed and each remaining slot repeated by its
     * {@link RecipeGraphSolver.RecipeInfo#ingredientSlotCount quantity}: the list a layout, a
     * {@link Batch} and {@link SlotAssignment} are indexed by.
     */
    static <R, K> List<List<K>> nonEmptySlots(RecipeGraphSolver.RecipeInfo<R, K> recipe) {
        return expand(recipe, recipe.ingredientSlots());
    }

    /**
     * The one place a recipe's slots become the slots a craft fills: {@code perSlot} (the recipe's
     * {@link RecipeGraphSolver.RecipeInfo#ingredientSlots()}, or a list aligned with it such as
     * {@link RecipeGraphSolver.RecipeInfo#craftableIngredientSlots()}) with the slots the recipe lists
     * empty dropped, and every other slot's entry repeated once per unit of its quantity. A slot
     * {@code perSlot} does not reach (an adapter that under-supplies) is the slot itself. A quantity
     * below 1 is an {@link IllegalArgumentException}, thrown the first time any pass reads the recipe.
     */
    static <R, K> List<List<K>> expand(RecipeGraphSolver.RecipeInfo<R, K> recipe, List<List<K>> perSlot) {
        List<List<K>> slots = recipe.ingredientSlots();
        List<List<K>> out = new ArrayList<>(slots.size());
        for (int s = 0; s < slots.size(); s++) {
            List<K> slot = slots.get(s);
            // Checked for an empty slot too: a bad quantity is an adapter bug wherever it sits.
            int quantity = recipe.ingredientSlotCount(s);
            if (quantity < 1) {
                throw new IllegalArgumentException("recipe " + recipe.id() + " slot " + s + " has quantity "
                        + quantity + ", which must be at least 1");
            }
            if (slot.isEmpty()) {
                continue;
            }
            List<K> entry = s < perSlot.size() ? perSlot.get(s) : slot;
            for (int copy = 0; copy < quantity; copy++) {
                out.add(entry);
            }
        }
        return out;
    }

    /** One chunk of {@link #run}: {@code repeat} consecutive crafts that all took {@code chosen}. */
    static final class Chunk<K> {
        /** How many crafts used this assignment (>= 1). */
        final long repeat;
        /** Per non-empty slot (see {@link #nonEmptySlots}), the item taken; null where unfilled. */
        final Object[] chosen;

        Chunk(long repeat, Object[] chosen) {
            this.repeat = repeat;
            this.chosen = chosen;
        }
    }

    /**
     * Runs {@code crafts} crafts of {@code recipe} against {@code inv}, mutating it. A short craft
     * consumes what it could assign and credits nothing; with {@code stopAtShort} the run ends at the
     * first one instead (before mutating anything for it).
     */
    static <R, K> Batch<K> run(RecipeGraphSolver.RecipeInfo<R, K> recipe, long crafts, UndoMap<K, Long> inv,
                               UnaryOperator<K> remainder, boolean stopAtShort) {
        return run(recipe, crafts, inv, remainder, stopAtShort, null);
    }

    /**
     * {@link #run} that additionally reports each chunk it applied: {@code chunks} (a fresh list per
     * call) receives one entry per chunk of crafts that shared one assignment, in craft order — the
     * exact pass records them as the step's layout runs. The chunks of a run that never came up
     * short hold no null {@code chosen} entry.
     */
    static <R, K> Batch<K> run(RecipeGraphSolver.RecipeInfo<R, K> recipe, long crafts, UndoMap<K, Long> inv,
                               UnaryOperator<K> remainder, boolean stopAtShort, List<Chunk<K>> chunks) {
        List<List<K>> slots = nonEmptySlots(recipe);
        Batch<K> batch = new Batch<>(slots);
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
            // Repeat this assignment while no listed alternative's capped count moves
            // (SlotAssignment.stableRepeats, the rule shared with the executors' batched craft).
            long repeat = SlotAssignment.stableRepeats(slots, item -> count(inv, item), use, made, left);
            if (chunks != null) {
                chunks.add(new Chunk<>(repeat, chosen));
            }
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
