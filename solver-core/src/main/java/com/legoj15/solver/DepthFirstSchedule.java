package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The depth-first walk of a long plan: {@code RecipeGraphSolver.solveLong} records one step per
 * recipe whose crafts count can reach ~1e13, so executing the steps in plan order would make every
 * intermediate in full before anything consumes it. This schedule picks the next batch at run time
 * from a model of the plan's own intermediates: a step may run only while the model holds what its
 * current layout consumes, and a shortage is met by demanding the producing step first — expanding
 * the plan depth first from the target, so no intermediate is ever held much beyond one batch.
 *
 * <p>Batches are sized in ITEMS, not crafts: {@code cap} (initially {@link #MAX_CAP}, so a roomy
 * storage sees the same batch counts the int-sized plan produced) bounds a batch's input and output
 * volume at {@code cap / max(resultCount, slotCount)} crafts. {@link #noRoom()} halves {@code cap}
 * when a batch's output had nowhere to go and {@link #performed} doubles it back after a batch ran
 * whole, so the batch tracks the storage's real free room.
 *
 * <p>The model holds one count per item some step produces (seeded from the planning snapshot:
 * {@link Long#MAX_VALUE} is unbounded and stays so); items no step produces are raw and assumed
 * available. The model knows no crafting remainders and no storage cap — execution re-checks every
 * craft against the live inventory, and a shortage the model did not foresee is real.
 *
 * <p>Authored in Java 8 like the rest of this module (the legacy loaders run a Java 8 runtime).
 *
 * @param <K> item identity type
 */
public final class DepthFirstSchedule<K> {

    /** The largest batch cap: an int-sized volume, what a batch may move of any one item. */
    public static final long MAX_CAP = Integer.MAX_VALUE;

    private final long[] crafts;
    private final List<K> results;
    private final int[] resultCounts;
    /** The model's per-item stock; keys are exactly the items some step produces. */
    private final Map<K, Long> stock = new HashMap<>();
    /** Item -> the plan-order step indices that produce it. */
    private final Map<K, int[]> producers = new HashMap<>();
    /**
     * Per step, its layout runs' start crafts (prefix sums, one longer than the run list), so the
     * run covering a done count is found without rescanning; a step without a layout holds one
     * synthetic run spanning its whole crafts with no items.
     */
    private final long[][] runStarts;
    /** Per step and run, the items that run puts in the slots (aliasing the given layouts). */
    private final List<List<K>>[] runItems;
    /** Per step and run, its tracked items' per-craft use counts (slot occurrences), run order. */
    private final List<Map<K, Long>>[] runUses;
    private final long[] done;
    /** Steps still below their total; 0 means the schedule (and so the job) is finished. */
    private int unfinished;
    private long totalDone;
    private long cap = MAX_CAP;
    /**
     * Steps refused a single craft for want of room ({@link #noRoom}); {@link #demand} routes around
     * them, so another branch can run and free space. Cleared by any {@link #performed}.
     */
    private final Set<Integer> blocked = new HashSet<>();
    /** The cached current batch, computed lazily and cleared by {@link #performed}/{@link #noRoom}. */
    private boolean haveBatch;
    private int currentStep = -1;
    private long currentBatch;

    /**
     * @param crafts       each step's total crafts, in plan order (producers before consumers, the
     *                     target recipe last; every entry >= 1)
     * @param layouts      per step (aligned 1:1), its {@link SlotLayout} runs, whose crafts sum to
     *                     the step's {@code crafts}; an empty list is "no layout": the step has no
     *                     tracked inputs and its whole remainder is one run
     * @param results      per step, the item its recipe produces (null when unknown)
     * @param resultCounts per step, how many of {@code results} one craft yields (0 when unknown)
     * @param held         the planning inventory snapshot seeding the model (missing = 0,
     *                     {@link Long#MAX_VALUE} = unbounded)
     */
    public DepthFirstSchedule(long[] crafts, List<List<SlotLayout<K>>> layouts, List<K> results,
                              int[] resultCounts, Map<K, Long> held) {
        if (crafts == null || crafts.length == 0) {
            throw new IllegalArgumentException("a schedule needs at least one step");
        }
        if (layouts.size() != crafts.length || results.size() != crafts.length
                || resultCounts.length != crafts.length) {
            throw new IllegalArgumentException("layouts, results and resultCounts must align 1:1 with crafts");
        }
        this.crafts = crafts.clone();
        this.results = new ArrayList<>(results);
        this.resultCounts = resultCounts.clone();
        int steps = crafts.length;
        @SuppressWarnings("unchecked")
        List<List<K>>[] items = new List[steps];
        @SuppressWarnings("unchecked")
        List<Map<K, Long>>[] uses = new List[steps];
        this.runStarts = new long[steps][];
        this.runItems = items;
        this.runUses = uses;
        for (int i = 0; i < steps; i++) {
            if (crafts[i] < 1) {
                throw new IllegalArgumentException("crafts must be >= 1, got " + crafts[i]);
            }
            K result = this.results.get(i);
            if (result != null) {
                Long count = held.get(result);
                stock.put(result, count == null ? 0L : count);
                int[] making = producers.get(result);
                int[] grown = making == null ? new int[1] : Arrays.copyOf(making, making.length + 1);
                grown[grown.length - 1] = i;
                producers.put(result, grown);
            }
        }
        for (int i = 0; i < steps; i++) {
            runsOf(i, layouts.get(i));
        }
        this.done = new long[steps];
        this.unfinished = steps;
    }

    /** Splits step {@code i}'s layout runs into the start/item/use tables the walk reads. */
    private void runsOf(int i, List<SlotLayout<K>> runs) {
        List<SlotLayout<K>> list = runs == null ? new ArrayList<SlotLayout<K>>() : runs;
        if (list.isEmpty()) { // "no layout": one synthetic run of the whole step, no tracked inputs
            runStarts[i] = new long[] {0, crafts[i]};
            runItems[i] = new ArrayList<>();
            runItems[i].add(new ArrayList<K>());
            runUses[i] = new ArrayList<>();
            runUses[i].add(Collections.<K, Long>emptyMap());
            return;
        }
        long[] starts = new long[list.size() + 1];
        List<List<K>> perRun = new ArrayList<>(list.size());
        List<Map<K, Long>> uses = new ArrayList<>(list.size());
        for (int r = 0; r < list.size(); r++) {
            SlotLayout<K> run = list.get(r);
            starts[r + 1] = saturatingAdd(starts[r], run.crafts());
            perRun.add(run.items());
            Map<K, Long> use = new LinkedHashMap<>();
            for (K item : run.items()) {
                if (stock.containsKey(item)) { // raw items are not tracked
                    use.merge(item, 1L, Long::sum);
                }
            }
            uses.add(use);
        }
        runStarts[i] = starts;
        runItems[i] = perRun;
        runUses[i] = uses;
    }

    /** The step whose batch is due, or -1 once every step has reached its total. */
    public int current() {
        if (!haveBatch) {
            Choice next = finished() ? null : choose(); // null: only surplus steps were left
            currentStep = next == null ? -1 : next.step;
            currentBatch = next == null ? 0 : next.batch;
            haveBatch = true;
        }
        return currentStep;
    }

    /** The current batch's crafts (>= 1 while a job runs; 0 once finished). */
    public long batch() {
        current();
        return currentBatch;
    }

    /**
     * Whether the current batch is the job's very last: it runs the target step, every other step is
     * finished, and it holds exactly the crafts still missing — the craft the grid hand-off wants.
     */
    public boolean isFinalBatch() {
        if (current() < 0) {
            return false;
        }
        int top = crafts.length - 1;
        if (currentStep != top) {
            return false;
        }
        for (int i = 0; i < top; i++) {
            if (done[i] < crafts[i]) {
                return false;
            }
        }
        return currentBatch == crafts[top] - done[top];
    }

    /**
     * Records {@code n} crafts (1..{@link #batch()}) of the current batch: consumes the model stock
     * of the batch's layout items, credits the step's result, and — when the batch ran whole —
     * doubles {@code cap} back toward {@link #MAX_CAP} when the cap was what limited it.
     */
    public void performed(long n) {
        int step = current();
        if (step < 0) {
            throw new IllegalStateException("the schedule is finished");
        }
        if (n < 1 || n > currentBatch) {
            throw new IllegalArgumentException("crafts performed must be in [1, " + currentBatch + "], got " + n);
        }
        for (Map.Entry<K, Long> entry : runUses[step].get(runOf(step)).entrySet()) {
            long have = stock.get(entry.getKey());
            if (have != Long.MAX_VALUE) { // unbounded stays unbounded
                stock.put(entry.getKey(), Math.max(0L, have - saturate(n, entry.getValue())));
            }
        }
        K result = results.get(step);
        if (result != null && resultCounts[step] > 0) {
            long have = stock.get(result);
            if (have != Long.MAX_VALUE) {
                stock.put(result, saturatingAdd(have, saturate(n, resultCounts[step])));
            }
        }
        done[step] += n;
        if (done[step] == crafts[step]) {
            unfinished--;
        }
        totalDone = saturatingAdd(totalDone, n);
        // Only a whole batch the cap (not the demand) limited is evidence that a bigger one fits:
        // growing after every small demand-sized batch made a storage-bound job refuse far more
        // often, and each refusal is a storage call in the game.
        if (n == currentBatch && currentBatch >= Math.max(1L, cap / volume(step))) {
            cap = Math.min(MAX_CAP, cap * 2);
        }
        blocked.clear();
        haveBatch = false;
    }

    /**
     * The current batch's output had nowhere to go: halve {@code cap} (never below 1) so the next
     * batch is smaller, and drop the cached batch so the next pick re-derives from the model.
     *
     * @return false when nothing can shrink — the batch is a single craft and {@code cap} is already
     *         at or below that step's per-craft volume, so the storage is genuinely full
     */
    public boolean noRoom() {
        int step = current();
        if (step < 0) {
            return false;
        }
        long volume = volume(step);
        if (currentBatch == 1 && cap <= volume) {
            // Not even one craft of this step fits: try every other branch before calling the storage
            // full, since one that consumes held stock frees the room this step needs.
            blocked.add(step);
            haveBatch = false;
            return current() >= 0;
        }
        cap = Math.max(1L, Math.min(cap, saturate(currentBatch, volume)) / 2);
        haveBatch = false;
        return true;
    }

    public boolean finished() {
        return unfinished == 0;
    }

    /** Step {@code i}'s crafts performed so far. */
    public long done(int i) {
        return done[i];
    }

    /** Crafts performed across every step so far, pinned at {@link Long#MAX_VALUE}. */
    public long totalDone() {
        return totalDone;
    }

    /** The model's stock of {@code item}: -1 when no step produces it (raw, untracked). */
    public long stock(K item) {
        Long have = stock.get(item);
        return have == null ? -1L : have;
    }

    /** The batch cap in items (halved by {@link #noRoom}, doubled back by a whole {@link #performed}). */
    public long cap() {
        return cap;
    }

    /**
     * The next batch: expand the plan depth first from the target step ({@link #demand}). When that
     * demand is the target's own remaining crafts — the model already holds everything they need —
     * every other step still open is surplus: the planner counted held stock differently from the
     * model's first-come use of it, and running those steps would only make intermediates nothing
     * asks for (in play, trillions of gold ingots into a full network before the one crystal craft).
     * They are skipped ({@link #skipOpenSteps}), so that batch is also the job's final one.
     */
    private Choice choose() {
        int top = crafts.length - 1;
        if (done[top] >= crafts[top]) {
            skipOpenSteps();
            return null;
        }
        long remaining = crafts[top] - done[top];
        Choice next = demand(top, remaining, new HashSet<Integer>());
        if (next == null) {
            return null; // every branch the target needs is refused room
        }
        if (next.step == top && next.batch == remaining) {
            skipOpenSteps();
        }
        return next;
    }

    /** Marks every open step but the target as done without running it (see {@link #choose}). */
    private void skipOpenSteps() {
        int top = crafts.length - 1;
        for (int i = 0; i < top; i++) {
            if (done[i] < crafts[i]) {
                totalDone = saturatingAdd(totalDone, crafts[i] - done[i]);
                done[i] = crafts[i];
                unfinished--;
            }
        }
    }

    /**
     * Up to {@code want} crafts of step {@code i} as the next batch: {@code b} is the smallest of the
     * want, the step's remainder, its share of {@code cap} and what its current layout run still
     * holds. Every tracked layout item must be in model stock for all {@code b} crafts; a shortage
     * recurses into the first plan-order step that produces it and still has crafts left (returning
     * that demand instead), and an item no runnable producer makes shrinks {@code b} to the held
     * count — to 1 rather than 0, so the executor meets the real shortage and reports it.
     *
     * @param path the steps on the current recursion path; a producer on it is skipped, which bounds
     *             the depth by the step count and breaks self-feeding cycles
     */
    private Choice demand(int i, long want, Set<Integer> path) {
        boolean starved = false; // an input's producers are all blocked
        long b = want;
        b = Math.min(b, crafts[i] - done[i]);
        b = Math.min(b, Math.max(1L, cap / volume(i)));
        b = Math.min(b, runLeft(i));
        long take = b; // b routes the demand; take is what the held stock lets this batch run
        for (Map.Entry<K, Long> entry : runUses[i].get(runOf(i)).entrySet()) {
            long have = stock.get(entry.getKey());
            if (have == Long.MAX_VALUE) {
                continue;
            }
            long need = saturate(b, entry.getValue());
            if (have >= need) {
                continue;
            }
            path.add(i);
            int p;
            try {
                p = producerOf(entry.getKey(), path);
            } finally {
                path.remove(i);
            }
            if (p >= 0) {
                path.add(i);
                Choice deeper;
                try {
                    deeper = demand(p, ceilDiv(need - have, Math.max(1, resultCounts[p])), path);
                } finally {
                    path.remove(i);
                }
                if (deeper != null) {
                    return deeper;
                }
                starved = true; // everything below p is refused room: try the other inputs
            } else if (producerOf(entry.getKey(), path, true) >= 0) {
                starved = true; // its only producers are blocked
            }
            take = Math.min(take, have / entry.getValue()); // run on what is held
        }
        if (take == 0 && starved) {
            return null; // this step cannot run until room frees up
        }
        return new Choice(i, Math.max(1L, take));
    }

    /** The first plan-order step off {@code path} that produces {@code item} and has crafts left. */
    private int producerOf(K item, Set<Integer> path) {
        return producerOf(item, path, false);
    }

    /** {@link #producerOf(Object, Set)}, with blocked steps skipped unless {@code includeBlocked}. */
    private int producerOf(K item, Set<Integer> path, boolean includeBlocked) {
        int[] making = producers.get(item);
        if (making == null) {
            return -1;
        }
        for (int p : making) {
            if (done[p] < crafts[p] && !path.contains(p) && (includeBlocked || !blocked.contains(p))) {
                return p;
            }
        }
        return -1;
    }

    /** The step's per-craft volume: whichever of its result count and current layout is bigger. */
    private long volume(int i) {
        return Math.max(1L, Math.max(resultCounts[i], runItems[i].get(runOf(i)).size()));
    }

    /** The index of the layout run covering the step's done count (its last run once past the end). */
    private int runOf(int i) {
        long[] starts = runStarts[i];
        for (int r = 0; r + 1 < starts.length; r++) {
            if (done[i] < starts[r + 1]) {
                return r;
            }
        }
        return starts.length - 2;
    }

    /** The crafts left in the step's current layout run. */
    private long runLeft(int i) {
        return runStarts[i][runOf(i) + 1] - done[i];
    }

    /** The chosen batch: a step index and how many of its crafts to perform. */
    private static final class Choice {
        final int step;
        final long batch;

        Choice(int step, long batch) {
            this.step = step;
            this.batch = batch;
        }
    }

    /** Saturating multiply of non-negative counts. */
    private static long saturate(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    /** Saturating add of non-negative counts. */
    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    /** Ceiling division of a non-negative count by a positive divisor. */
    private static long ceilDiv(long a, long b) {
        return a / b + (a % b == 0 ? 0 : 1);
    }
}
