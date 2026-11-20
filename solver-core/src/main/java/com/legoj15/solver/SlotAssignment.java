package com.legoj15.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * The one rule for which stock fills which ingredient slot of a single craft. The solver plans with
 * it ({@link PlanSimulator}, and so every plan {@link RecipeGraphSolver#solve} returns) and every
 * executor calls it to pick the inventory stack for each grid slot, so a plan never depends on where
 * items happen to sit in the inventory.
 *
 * <p>The rule, deterministic for a given slot list and stock:
 * <ol>
 *   <li>Fill the most constrained slot next: the one with the fewest alternatives still in stock
 *       (ties: lowest slot index). An oak-only slot is filled before an oak-or-birch one.</li>
 *   <li>Within that slot, take the first alternative (preference order) that is not scarce, where
 *       scarce means its remaining stock is below the number of still-unfilled slots that accept it;
 *       if every alternative is scarce, the first one in stock.</li>
 *   <li>If a slot is left with nothing in stock while some full assignment exists, re-route earlier
 *       picks along an augmenting path (bipartite matching), so a craft that can be laid out is.</li>
 * </ol>
 *
 * <p>The choice reads each alternative's stock only up to the number of slots that list it; that is
 * what lets {@link PlanSimulator} replay a long batch in a few chunks. Duplicate alternatives within
 * one slot are tolerated but counted twice in the ordering heuristics (the game adapters dedupe).
 */
public final class SlotAssignment {

    private SlotAssignment() {
    }

    /**
     * Per slot (aligned with {@code slots}), the item to take from {@code stock} for one craft, or
     * null where nothing in stock can fill it. Never assigns an item more often than its count.
     *
     * @param slots each slot's acceptable items in preference order (the solver's
     *              {@link RecipeGraphSolver.RecipeInfo#ingredientSlots()} with blank cells removed)
     * @param stock current counts; absent or non-positive means none
     */
    public static <K> List<K> assign(List<? extends List<K>> slots, Map<K, ? extends Number> stock) {
        Object[] chosen = choose(slots, item -> {
            Number have = stock.get(item);
            return have == null ? 0 : have.longValue();
        });
        List<K> out = new ArrayList<>(chosen.length);
        for (Object item : chosen) {
            @SuppressWarnings("unchecked")
            K k = (K) item;
            out.add(k);
        }
        return out;
    }

    /**
     * {@link #assign} over a crafting grid's cells as the game lists them, blank cells included: a
     * null cell takes no part in the choice and comes back null, so the result lines up index for
     * index with the recipe's ingredient list. Every executor builds its per-cell preference here.
     */
    public static <K> List<K> assignCells(List<? extends List<K>> cells, Map<K, ? extends Number> stock) {
        List<List<K>> slots = new ArrayList<>();
        for (List<K> cell : cells) {
            if (cell != null) {
                slots.add(cell);
            }
        }
        List<K> chosen = assign(slots, stock);
        List<K> out = new ArrayList<>(cells.size());
        int s = 0;
        for (List<K> cell : cells) {
            out.add(cell == null ? null : chosen.get(s++));
        }
        return out;
    }

    /** {@link #assign} over a count function; null entries for slots left unfilled. */
    static <K> Object[] choose(List<? extends List<K>> slots, ToLongFunction<K> stock) {
        int n = slots.size();
        Map<K, long[]> remaining = new HashMap<>();
        for (List<K> slot : slots) {
            for (K item : slot) {
                if (!remaining.containsKey(item)) {
                    remaining.put(item, new long[] {Math.max(0, stock.applyAsLong(item))});
                }
            }
        }
        Object[] chosen = new Object[n];
        boolean[] done = new boolean[n];
        boolean anyUnfilled = false;
        for (int round = 0; round < n; round++) {
            int best = -1;
            int bestOptions = Integer.MAX_VALUE;
            for (int s = 0; s < n; s++) {
                if (!done[s]) {
                    int options = options(slots.get(s), remaining);
                    if (options < bestOptions) {
                        best = s;
                        bestOptions = options;
                    }
                }
            }
            done[best] = true;
            if (bestOptions == 0) {
                anyUnfilled = true;
                continue;
            }
            K pick = null;
            K firstInStock = null;
            for (K item : slots.get(best)) {
                long left = remaining.get(item)[0];
                if (left <= 0) {
                    continue;
                }
                if (firstInStock == null) {
                    firstInStock = item;
                }
                if (left >= 1 + demand(item, slots, done)) {
                    pick = item;
                    break;
                }
            }
            if (pick == null) {
                pick = firstInStock;
            }
            remaining.get(pick)[0]--;
            chosen[best] = pick;
        }
        if (anyUnfilled) {
            for (int s = 0; s < n; s++) {
                if (chosen[s] == null && !slots.get(s).isEmpty()) {
                    augment(s, slots, chosen, remaining, new boolean[n]);
                }
            }
        }
        return chosen;
    }

    /** Alternatives of {@code slot} with stock left. */
    private static <K> int options(List<K> slot, Map<K, long[]> remaining) {
        int options = 0;
        for (K item : slot) {
            if (remaining.get(item)[0] > 0) {
                options++;
            }
        }
        return options;
    }

    /** Unfilled slots (other than the one being filled, already marked done) that accept {@code item}. */
    private static <K> int demand(K item, List<? extends List<K>> slots, boolean[] done) {
        int demand = 0;
        for (int s = 0; s < slots.size(); s++) {
            if (!done[s] && slots.get(s).contains(item)) {
                demand++;
            }
        }
        return demand;
    }

    /**
     * Kuhn's augmenting step: fills unfilled slot {@code s} from stock, or takes an item another slot
     * holds when that slot can move to something else (recursively). Leaves everything as it was on
     * failure.
     */
    private static <K> boolean augment(int s, List<? extends List<K>> slots, Object[] chosen,
                                       Map<K, long[]> remaining, boolean[] visited) {
        for (K item : slots.get(s)) {
            long[] left = remaining.get(item);
            if (left[0] > 0) {
                left[0]--;
                chosen[s] = item;
                return true;
            }
        }
        for (K item : slots.get(s)) {
            for (int t = 0; t < chosen.length; t++) {
                if (visited[t] || !Objects.equals(chosen[t], item)) {
                    continue;
                }
                visited[t] = true;
                // s takes t's item; t must find another (item has no stock left, so it cannot take it back).
                chosen[t] = null;
                chosen[s] = item;
                if (augment(t, slots, chosen, remaining, visited)) {
                    return true;
                }
                chosen[s] = null;
                chosen[t] = item;
            }
        }
        return false;
    }
}
