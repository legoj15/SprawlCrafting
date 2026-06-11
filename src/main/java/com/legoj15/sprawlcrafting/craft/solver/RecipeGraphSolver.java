package com.legoj15.sprawlcrafting.craft.solver;

import java.util.ArrayList;
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
 * <p>Resolution rules (DESIGN.md):
 * <ul>
 *   <li>Stock first: items already (virtually) in the inventory satisfy a slot before any
 *       sub-craft is planned.</li>
 *   <li>Each ingredient slot lists its acceptable alternatives in preference order; the
 *       first alternative in stock wins, then the first alternative that can be crafted.</li>
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

        K result();

        int resultCount();
    }

    /** Outcome of a solve: either an executable plan or a best-effort set of missing items. */
    public sealed interface Result<R, K> {
        record Success<R, K>(List<PlannedStep<R>> steps) implements Result<R, K> {}

        /**
         * @param missing        representative items from failed branches — UX hints, not exhaustive
         * @param budgetExceeded true if the search was aborted because the recipe graph was
         *                       too branchy to prove solvable or unsolvable within budget
         */
        record Failure<R, K>(Set<K> missing, boolean budgetExceeded) implements Result<R, K> {}
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

    /** Mutable per-solve state: the search budget and failure diagnostics. */
    private final class Solve {
        int attemptsLeft = maxAttempts;
        final Set<K> missing = new LinkedHashSet<>();

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
            for (List<K> slot : recipe.ingredientSlots()) {
                if (slot.isEmpty()) {
                    continue;
                }
                if (!satisfySlot(slot, inv, steps, crafting, depth, pendingRemainders)) {
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
            for (K item : alternatives) {
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
