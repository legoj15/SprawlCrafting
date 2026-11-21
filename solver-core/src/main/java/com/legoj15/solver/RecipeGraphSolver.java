package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The Factorio-style planning core: given a target recipe and a snapshot of the player's
 * inventory, produce an ordered list of crafts where every step's ingredients are either
 * already in the inventory or produced by an earlier step. Pure Java — no Minecraft types —
 * so the whole algorithm is unit-testable; {@code CraftPlanner} adapts game types onto it.
 *
 * <p>Authored in Java 8 (plain classes/interfaces, no records or sealed types) because this
 * library ({@code com.legoj15:solver}) is the single planner every loader build bundles —
 * including the legacy-Forge 1.12.2 target, whose runtime is Java 8. The public surface (accessor method names, the
 * {@code Result.Success}/{@code Result.Failure} subtypes) is unchanged from the former record
 * form, so the modern callers and the test suite need no edits.
 *
 * <p>Resolution rules (DESIGN.md):
 * <ul>
 *   <li>Planning is in QUANTITIES: a recipe is resolved once per batch of crafts, not once per
 *       unit, so a 9x compression chain ten levels deep costs ten resolver visits rather than
 *       billions. Identical ingredient slots of one recipe are aggregated (eight plank slots =
 *       one demand for eight planks). If that batched pass fails, a second pass takes one unit
 *       per slot in slot order — the pre-batching planner exactly — so a recipe whose slots
 *       compete for shared stock (oak-or-birch, oak, oak-or-birch) is never lost to the batching
 *       order.</li>
 *   <li>Executable as run: every plan {@link #solve} returns carries its own per-slot item choice —
 *       {@link Result.Success#layouts()} fixes, for each step, the item every craft puts in every
 *       non-empty slot, as the pass that built the step decided — and a plan is accepted only when it
 *       replays under those fixed layouts (a plain count check, see {@code replays}). The executors
 *       follow the layouts and fall back to {@link SlotAssignment} only when the inventory no longer
 *       matches, so a sub-craft whose slot prefers an item a later step needs (and holds the only
 *       copy of) is told to take the other alternative instead of eating it (#23). Only the exact
 *       pass still fills its slots with {@link SlotAssignment} as it plans (it is the contention
 *       fallback), so that rule's inability to look ahead limits the exact pass alone; a target it
 *       cannot lay out fails rather than stalling mid-job.</li>
 *   <li>Stock first: items already (virtually) in the inventory satisfy a slot before any
 *       sub-craft is planned.</li>
 *   <li>Each ingredient slot lists its acceptable alternatives in preference order; stock is
 *       drawn across them in that order, then the remainder is crafted from the first
 *       craftable alternative, spilling over to the next when one runs short (one oak log plus
 *       one birch log make eight mixed planks). An alternative satisfiable only by a specific
 *       NBT (a lava-filled bucket) is matchable from stock but not craftable — see
 *       {@link RecipeInfo#craftableIngredientSlots()}.</li>
 *   <li>Candidate recipes for a missing item are tried in the order supplied by
 *       {@code producers}; the first supplies as much as it can, the next covers the rest
 *       (deterministic).</li>
 *   <li>A batch that cannot be completed is undone and re-resolved smaller — at the count its
 *       short slot actually supplied, then (if an earlier slot may have competed for it) at one
 *       craft — so no sub-craft is planned whose output the batch would then not consume; the
 *       caller asks again for the rest.</li>
 *   <li>Cycles (iron ingot ⇄ iron block) are broken by refusing to sub-craft an item that
 *       is already being crafted higher up the same chain; a depth limit backstops it.</li>
 *   <li>Crafting remainders (milk bucket → bucket) become available only when the craft
 *       that consumes the item <em>executes</em> — i.e. after the consuming recipe's own
 *       step (N consumed, N remainders, after that step) — matching real execution order, not
 *       at virtual-consume time.</li>
 *   <li>The search is budgeted: pathological recipe graphs (vanilla's 16-color bed cycle
 *       under the wrong inventory, or hostile datapacks) would otherwise explore a
 *       factorial number of branches on the server thread. Exhausting the budget yields
 *       a {@link Result.Failure} with {@code budgetExceeded} set.</li>
 *   <li>Quantities are {@code long} with saturating arithmetic: they never wrap. A batch above
 *       {@link Integer#MAX_VALUE} crafts (unexecutable as a {@link PlannedStep}) fails cleanly.</li>
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

        /**
         * An executable plan: the ordered, merged list of steps to run, each with the per-slot item
         * choice its pass decided.
         */
        final class Success<R, K> implements Result<R, K> {
            private final List<PlannedStep<R>> steps;
            private final List<List<SlotLayout<K>>> layouts;

            /**
             * A plan whose steps carry no layout: an executor falls back to {@link SlotAssignment}
             * for every slot of every craft.
             */
            public Success(List<PlannedStep<R>> steps) {
                this.steps = steps;
                this.layouts = new ArrayList<>(Collections.<List<SlotLayout<K>>>nCopies(
                        steps.size(), Collections.<SlotLayout<K>>emptyList()));
            }

            /**
             * @param steps   the ordered steps to run
             * @param layouts per step (aligned 1:1 with {@code steps}), the step's {@link SlotLayout}
             *                runs, or an empty list for "no layout": consecutive crafts with identical
             *                layouts compressed, so adjacent runs never list equal items; every run of
             *                a step lists the same number of slots; a non-empty run list sums to the
             *                step's crafts
             * @throws IllegalArgumentException when the two lists are not aligned 1:1 or a step's
             *                                  runs break those invariants
             */
            public Success(List<PlannedStep<R>> steps, List<List<SlotLayout<K>>> layouts) {
                if (layouts.size() != steps.size()) {
                    throw new IllegalArgumentException("layouts must align 1:1 with steps: "
                            + layouts.size() + " layouts for " + steps.size() + " steps");
                }
                for (int i = 0; i < steps.size(); i++) {
                    List<SlotLayout<K>> runs = layouts.get(i);
                    long crafts = 0;
                    for (int r = 0; r < runs.size(); r++) {
                        crafts += runs.get(r).crafts();
                        // Without the recipe this cannot check arity against the slots, but every run
                        // of one step lays out the same slots.
                        if (r > 0 && runs.get(r).items().size() != runs.get(0).items().size()) {
                            throw new IllegalArgumentException("step " + i + " run " + r + " lists "
                                    + runs.get(r).items().size() + " slots, run 0 lists "
                                    + runs.get(0).items().size());
                        }
                        if (r > 0 && Objects.equals(runs.get(r).items(), runs.get(r - 1).items())) {
                            throw new IllegalArgumentException("step " + i + " runs " + (r - 1) + " and "
                                    + r + " list equal items: consecutive runs must be merged");
                        }
                    }
                    if (!runs.isEmpty() && crafts != steps.get(i).crafts()) {
                        throw new IllegalArgumentException("step " + i + " runs sum to " + crafts
                                + " crafts, step holds " + steps.get(i).crafts());
                    }
                }
                this.steps = steps;
                this.layouts = layouts;
            }

            public List<PlannedStep<R>> steps() {
                return steps;
            }

            /**
             * Per step (aligned 1:1 with {@link #steps()}), the step's layout runs in craft order:
             * run {@code r} says the next {@code crafts()} crafts put {@code items().get(i)} in
             * non-empty slot {@code i}. An empty list per step means "no layout": an executor uses
             * {@link SlotAssignment}.
             */
            public List<List<SlotLayout<K>>> layouts() {
                return layouts;
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
            private final boolean tooLarge;

            public Failure(Set<K> missing, boolean budgetExceeded) {
                this(missing, budgetExceeded, false);
            }

            /**
             * @param tooLarge true if a plan exists but is longer than {@code maxSteps} int-sized
             *                 batches (implies {@code budgetExceeded}, which older callers read)
             */
            public Failure(Set<K> missing, boolean budgetExceeded, boolean tooLarge) {
                this.tooLarge = tooLarge;
                this.missing = missing;
                this.budgetExceeded = budgetExceeded;
            }

            public Set<K> missing() {
                return missing;
            }

            public boolean budgetExceeded() {
                return budgetExceeded;
            }

            /** The target is makeable from stock, but its plan would not fit in {@code maxSteps}. */
            public boolean tooLarge() {
                return tooLarge;
            }
        }
    }

    /**
     * Net raw-gatherable demand from a {@link #shortfall} query.
     *
     * <p>{@link #demandsLong()} is the exact (saturating) count: leaf items (those with no usable
     * producer recipe — the true gatherables) mapped to the count the player must acquire, keyed
     * by the representative alternative that was chosen. Deep compression trees legitimately need
     * more than {@link Integer#MAX_VALUE} of a raw; {@link #demands()} is the legacy int view with
     * each count clamped to {@link Integer#MAX_VALUE}.
     *
     * <p>{@link #alternatives()}: per representative item, the full ordered set of items that
     * satisfy the slot it was gathered for (so the UI can cycle "coal or charcoal", "any log").
     *
     * <p>{@link #approximate()}: true if the depth/attempt budget was hit during the walk, or the
     * planner would still not accept the list once gathered, so the map may be off (possibly
     * short); a UX caveat, not an error. {@link #saturated()}: true if any count reached
     * {@link Long#MAX_VALUE} (the true figure is at least that).
     */
    public static final class ShortfallResult<K> {
        private final Map<K, Long> demandsLong;
        private final Map<K, Integer> demands;
        private final Map<K, List<K>> alternatives;
        private final boolean approximate;
        private final boolean saturated;

        public ShortfallResult(Map<K, Long> demandsLong, Map<K, List<K>> alternatives,
                               boolean approximate, boolean saturated) {
            this.demandsLong = demandsLong;
            this.demands = new HashMap<>();
            demandsLong.forEach((k, v) -> demands.put(k, (int) Math.min(v, Integer.MAX_VALUE)));
            this.alternatives = alternatives;
            this.approximate = approximate;
            this.saturated = saturated;
        }

        /** Legacy int-valued constructor; never saturated. */
        public ShortfallResult(Map<K, Integer> demands, Map<K, List<K>> alternatives,
                               boolean approximate) {
            this.demands = demands;
            this.demandsLong = new HashMap<>();
            demands.forEach((k, v) -> demandsLong.put(k, (long) v));
            this.alternatives = alternatives;
            this.approximate = approximate;
            this.saturated = false;
        }

        /** Counts clamped to {@link Integer#MAX_VALUE}; see {@link #demandsLong()} for exact ones. */
        public Map<K, Integer> demands() {
            return demands;
        }

        public Map<K, Long> demandsLong() {
            return demandsLong;
        }

        public Map<K, List<K>> alternatives() {
            return alternatives;
        }

        public boolean approximate() {
            return approximate;
        }

        public boolean saturated() {
            return saturated;
        }
    }

    /** Extra walk rounds {@link #shortfall} may add when the planner rejects the first list. */
    private static final int TOP_UP_ROUNDS = 2;

    /** Sub-craft-and-replay rounds the exact pass gives one batch before shrinking it. */
    private static final int EXACT_ROUNDS = 4;

    private final Function<K, List<? extends RecipeInfo<R, K>>> producers;
    private final UnaryOperator<K> remainder;
    private final int maxDepth;
    private final int maxAttempts;

    /**
     * @param producers   recipes that produce a given item, in deterministic preference order
     * @param remainder   what consuming one of an item leaves behind (empty bucket), or null
     * @param maxDepth    recursion backstop on the recipe tree depth
     * @param maxAttempts resolver node visits (one recipe resolved for one batch size) allowed per
     *                    planning pass before aborting — bounds worst-case backtracking on hostile
     *                    graphs. {@link #solve} runs at most three passes (batched, slot by slot, exact);
     *                    a {@link #shortfall} walk gets this many search visits plus this many
     *                    expansion visits, and each of its verifications is a {@link #solve}-sized run.
     */
    public RecipeGraphSolver(Function<K, List<? extends RecipeInfo<R, K>>> producers,
                             UnaryOperator<K> remainder,
                             int maxDepth,
                             int maxAttempts) {
        this(producers, remainder, maxDepth, maxAttempts, DEFAULT_MAX_STEPS);
    }

    /**
     * @param maxSteps longest plan (in batches of at most {@link Integer#MAX_VALUE} crafts) a quantity-
     *                 driven tree may be given; see {@link Result.Failure#tooLarge()}. It bounds the
     *                 time and size of the guided pass, whose kept steps cost no attempts.
     */
    public RecipeGraphSolver(Function<K, List<? extends RecipeInfo<R, K>>> producers,
                             UnaryOperator<K> remainder,
                             int maxDepth,
                             int maxAttempts,
                             int maxSteps) {
        this.maxSteps = maxSteps;
        this.producers = producers;
        this.remainder = remainder;
        this.maxDepth = maxDepth;
        this.maxAttempts = maxAttempts;
    }

    /** Default {@code maxSteps}: about 3.5e13 crafts of one recipe. */
    public static final int DEFAULT_MAX_STEPS = 16384;
    private final int maxSteps;

    public Result<R, K> solve(RecipeInfo<R, K> target, Map<K, Integer> inventory) {
        Plan<R, K> plan = plan(target, toLong(inventory), true);
        if (plan.steps == null) {
            return new Result.Failure<>(plan.missing, plan.budgetExceeded, plan.tooLarge);
        }
        return mergeAdjacent(plan.steps);
    }

    /**
     * Collapses consecutive identical recipe ids into one step (a {@link PlannedStep} holds an int,
     * so a pair whose sum overflows stays two steps), concatenating their layout runs — a boundary
     * pair that lists equal items is one run, or {@link Result.Success} would reject the merge.
     */
    private static <R, K> Result<R, K> mergeAdjacent(List<Step<R, K>> plan) {
        List<PlannedStep<R>> steps = new ArrayList<>(plan.size());
        List<List<SlotLayout<K>>> layouts = new ArrayList<>(plan.size());
        for (Step<R, K> step : plan) {
            int last = steps.size() - 1;
            if (last >= 0 && steps.get(last).recipeId().equals(step.recipe.id())
                    && (long) steps.get(last).crafts() + step.crafts <= Integer.MAX_VALUE) {
                steps.set(last, steps.get(last).withCrafts(steps.get(last).crafts() + (int) step.crafts));
                layouts.set(last, concatRuns(layouts.get(last), step.runs));
            } else {
                steps.add(new PlannedStep<>(step.recipe.id(), (int) step.crafts));
                layouts.add(step.runs);
            }
        }
        return new Result.Success<>(steps, layouts);
    }

    /** Two steps' runs concatenated, merging a boundary pair that lists equal items. */
    private static <K> List<SlotLayout<K>> concatRuns(List<SlotLayout<K>> first, List<SlotLayout<K>> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        List<SlotLayout<K>> out = new ArrayList<>(first.size() + second.size());
        out.addAll(first);
        SlotLayout<K> boundary = second.get(0);
        SlotLayout<K> tail = out.get(out.size() - 1);
        if (tail.items().equals(boundary.items())) {
            out.set(out.size() - 1, new SlotLayout<>(tail.crafts() + boundary.crafts(), boundary.items()));
            out.addAll(second.subList(1, second.size()));
        } else {
            out.addAll(second);
        }
        return out;
    }

    /**
     * One satisfied batch's consumption as layout runs. Each group's units (its per-item takes, in
     * preference order) form one sequence; unit {@code u} of a group with {@code count} slots goes
     * to craft {@code u / count}, the group's {@code (u % count)}-th slot — every slot of a group
     * lists the same alternatives, so any consumed item fits any of them. Crafts with identical
     * full layouts compress into one run.
     */
    private static <K> List<SlotLayout<K>> layoutRuns(long crafts, List<Group<K>> groups, List<Map<K, Long>> use) {
        // Only recorded steps get layouts, and craft() caps a recorded batch at an int (capInt).
        if (crafts > Integer.MAX_VALUE) {
            throw new IllegalStateException("a recorded step holds " + crafts + " crafts, above an int");
        }
        int slotCount = 0;
        for (Group<K> group : groups) {
            slotCount += group.slotIndexes.size();
        }
        // Per group, its consumed items as contiguous segments (preference order): segment i ends,
        // exclusive, at ends[i] units into the group's block.
        List<List<K>> segmentItems = new ArrayList<>(groups.size());
        List<long[]> segmentEnds = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) {
            Group<K> group = groups.get(g);
            List<K> items = new ArrayList<>();
            long[] ends = new long[group.alternatives.size()];
            long at = 0;
            for (K item : group.alternatives) {
                Long units = use.get(g).get(item);
                long n = units == null ? 0 : units;
                if (n > 0) {
                    at += n;
                    ends[items.size()] = at;
                    items.add(item);
                }
            }
            segmentItems.add(items);
            segmentEnds.add(Arrays.copyOf(ends, items.size()));
        }
        // A craft's layout can only differ from the previous craft's where a group's item boundary
        // falls inside the craft's own unit block or straddles the two: with a boundary at unit p of
        // a count-slot group, that is craft p/count or the one after. Inspecting those candidates
        // (plus the first craft) finds every change without walking all crafts — a 6561-craft batch
        // of one-item groups is still a single run.
        TreeSet<Long> candidates = new TreeSet<>();
        candidates.add(0L);
        for (int g = 0; g < groups.size(); g++) {
            long count = groups.get(g).count;
            long[] ends = segmentEnds.get(g);
            for (int i = 0; i + 1 < ends.length; i++) {
                candidates.add(ends[i] / count);
                candidates.add(ends[i] / count + 1);
            }
        }
        List<SlotLayout<K>> runs = new ArrayList<>();
        long start = 0;
        List<K> layout = layoutAt(0, groups, segmentItems, segmentEnds, slotCount);
        for (long c : candidates) {
            if (c <= start) {
                continue;
            }
            if (c >= crafts) {
                break;
            }
            List<K> at = layoutAt(c, groups, segmentItems, segmentEnds, slotCount);
            if (!at.equals(layout)) {
                runs.add(new SlotLayout<>((int) (c - start), layout));
                start = c;
                layout = at;
            }
        }
        runs.add(new SlotLayout<>((int) (crafts - start), layout));
        return runs;
    }

    /** The full slot layout of craft {@code c}: every group's block of units, laid on its slots. */
    private static <K> List<K> layoutAt(long c, List<Group<K>> groups, List<List<K>> segmentItems,
                                        List<long[]> segmentEnds, int slotCount) {
        List<K> items = new ArrayList<>(Collections.<K>nCopies(slotCount, null));
        for (int g = 0; g < groups.size(); g++) {
            Group<K> group = groups.get(g);
            int count = (int) group.count;
            long[] ends = segmentEnds.get(g);
            List<K> segItems = segmentItems.get(g);
            long unit = c * count;
            for (int j = 0; j < count; j++, unit++) {
                int seg = 0;
                while (seg + 1 < ends.length && unit >= ends[seg]) {
                    seg++;
                }
                items.set(group.slotIndexes.get(j), segItems.get(seg));
            }
        }
        return items;
    }

    /**
     * One exact-pass replay's chunks as layout runs. Every craft of a chunk was laid out the same
     * way by construction, so a chunk is a run; a chunk boundary that repeats the previous
     * assignment merges, keeping the runs canonical (never two adjacent with equal items).
     */
    @SuppressWarnings("unchecked")
    private static <K> List<SlotLayout<K>> chunkRuns(List<PlanSimulator.Chunk<K>> chunks) {
        List<SlotLayout<K>> runs = new ArrayList<>(chunks.size());
        for (PlanSimulator.Chunk<K> chunk : chunks) {
            if (chunk.repeat > Integer.MAX_VALUE) { // a recorded batch is capped at an int (capInt)
                throw new IllegalStateException("a recorded chunk holds " + chunk.repeat + " crafts, above an int");
            }
            int repeat = (int) chunk.repeat;
            List<K> items = new ArrayList<>(chunk.chosen.length);
            for (Object item : chunk.chosen) {
                items.add((K) item);
            }
            int last = runs.size() - 1;
            if (last >= 0 && runs.get(last).items().equals(items)) {
                SlotLayout<K> tail = runs.get(last);
                runs.set(last, new SlotLayout<>(tail.crafts() + repeat, items));
            } else {
                runs.add(new SlotLayout<>(repeat, items));
            }
        }
        return runs;
    }

    /**
     * Up to three planning passes over {@code held}, each result accepted only if it replays from
     * {@code held} under the per-slot layouts the pass itself decided (see {@link #replays}; the
     * executors follow those layouts, so that replay — not a {@link SlotAssignment} one — is the
     * contract a plan must meet):
     * <ol>
     *   <li>batched: each slot group's whole demand at once (fast, the usual answer);</li>
     *   <li>unitwise: one unit per slot in slot order, the pre-batching planner, so a recipe whose
     *       slots compete for shared stock in a way batching loses is still found;</li>
     *   <li>exact: only when a pass's plan was rejected or a pass came up short on an item another
     *       slot group had taken (contention). Every step's consumption is a {@link PlanSimulator}
     *       replay applied at the moment the step is emitted, so stock a later step counted on is
     *       never "reserved" in the model while an earlier step physically eats it (#10), and slots
     *       are filled most constrained first (#18). This pass still decides items with
     *       {@link SlotAssignment}, which cannot look ahead — the layouts carry its choice.</li>
     * </ol>
     *
     * @param capInt cap a batch at {@link Integer#MAX_VALUE} crafts (what a {@link PlannedStep} holds)
     */
    private Plan<R, K> plan(RecipeInfo<R, K> target, Map<K, Long> held, boolean capInt) {
        List<Step<R, K>> steps = new ArrayList<>();
        Solve batched = new Solve(true, true, false, capInt);
        boolean batchedRejected = false;
        if (batched.craft(target, 1, new UndoMap<>(held), steps, new HashSet<K>(), 0) == 1) {
            if (replays(steps, held)) {
                return new Plan<>(steps, null, false);
            }
            batchedRejected = true;
        }
        steps.clear();
        Solve unitwise = new Solve(false, true, false, capInt);
        boolean unitwiseRejected = false;
        if (unitwise.craft(target, 1, new UndoMap<>(held), steps, new HashSet<K>(), 0) == 1) {
            if (replays(steps, held)) {
                return new Plan<>(steps, null, false);
            }
            unitwiseRejected = true;
        }
        Set<K> missing = new LinkedHashSet<>(batched.missing);
        missing.addAll(unitwise.missing);
        // Too complex only if no pass failed cleanly (a rejected plan is not a clean failure) and one
        // ran out of budget: a clean batched failure is a real shortfall even when the per-unit walk
        // ran out of budget on the same (large) tree.
        boolean noCleanFailure = (batched.budgetHit || batchedRejected) && (unitwise.budgetHit || unitwiseRejected);
        boolean anyBudget = batched.budgetHit || unitwise.budgetHit;
        boolean clipped = batched.clipped || unitwise.clipped;
        if (batchedRejected || unitwiseRejected || batched.contention || unitwise.contention) {
            steps.clear();
            Solve exact = new Solve(false, true, true, capInt);
            boolean exactRejected = false;
            if (exact.craft(target, 1, new UndoMap<>(held), steps, new HashSet<K>(), 0) == 1) {
                if (replays(steps, held)) {
                    return new Plan<>(steps, null, false);
                }
                exactRejected = true; // not expected: the exact pass applies the replay as it plans
            }
            missing.addAll(exact.missing);
            noCleanFailure &= exact.budgetHit || exactRejected;
            anyBudget |= exact.budgetHit;
            clipped |= exact.clipped;
        }
        boolean budgetExceeded = noCleanFailure && anyBudget;
        if (capInt && (budgetExceeded || clipped)) {
            return planByQuantity(target, held, missing);
        }
        return new Plan<>(null, missing, budgetExceeded);
    }

    /**
     * The answer for a tree the budgeted passes gave up on, or failed on after splitting a batch at
     * the int cap. Their visits grow with the quantities (one per int-sized batch, more when a
     * large stock runs short part-way), so running out says nothing about the tree; and between
     * two int-sized batches of one item, an unpacking recipe tried first can take the first batch
     * apart again, which ends in a clean failure for a tree that is makeable. A probe first decides it without that: the batched search with long
     * batches, nothing recorded and no futile re-asks visits each recipe a handful of times
     * whatever the counts. A clean probe failure is a real shortfall. A probe success names the
     * recipes a plan uses, and the batched pass is run again over just those, so no dead-end
     * candidate is retried per batch; its kept steps cost no attempts, and {@code maxSteps} bounds
     * it instead.
     *
     * <p>A pass that split a batch can fail cleanly on a makeable tree, so its own verdict is not
     * kept: the probe's is, and where the probe proves nothing the answer is "budget exceeded".
     *
     * @param missing the budgeted passes' missing set, kept when the probe has nothing better
     */
    private Plan<R, K> planByQuantity(RecipeInfo<R, K> target, Map<K, Long> held, Set<K> missing) {
        // First with every re-ask: a generating cycle (one i1 makes three i0, one i0 makes one i1)
        // only reaches its count by asking again after a batch that shrank. Where that probe runs
        // out of budget chasing leftovers, the one that skips futile re-asks answers instead.
        Solve probe = probe(target, held, false);
        if (probe == null) {
            probe = probe(target, held, true);
        }
        if (probe == null) {
            return new Plan<>(null, missing, true);
        }
        if (probe.used == null) {
            // The probe is the batched strategy alone: where it came up short on an item that a
            // slot group with a choice of items had taken (or the short group has such a choice), a
            // different assignment might have worked, and its failure proves nothing. Groups that
            // each accept one item demand the same total in any order.
            return probe.choiceContention
                    ? new Plan<>(null, missing, true)
                    : new Plan<>(null, new LinkedHashSet<>(probe.missing), false);
        }
        List<Step<R, K>> steps = new ArrayList<>();
        Solve guided = new Solve(true, true, false, true);
        guided.quantityFree = true;
        guided.allowed = Collections.newSetFromMap(new IdentityHashMap<RecipeInfo<R, K>, Boolean>());
        guided.allowed.addAll(probe.used);
        if (guided.craft(target, 1, new UndoMap<>(held), steps, new HashSet<K>(), 0) == 1 && replays(steps, held)) {
            return new Plan<>(steps, null, false);
        }
        return new Plan<>(null, missing, true, guided.tooLong);
    }

    /**
     * One probe run: null if it ran out of budget, else the pass, whose {@code used} lists the
     * recipes of a plan, or is null when the target cannot be made.
     */
    private Solve probe(RecipeInfo<R, K> target, Map<K, Long> held, boolean skipFutileReasks) {
        Solve probe = new Solve(true, true, false, false);
        probe.quantityFree = true;
        probe.skipFutileReasks = skipFutileReasks;
        probe.used = new ArrayList<>();
        if (probe.craft(target, 1, new UndoMap<>(held), null, new HashSet<K>(), 0) == 1) {
            return probe;
        }
        probe.used = null;
        return probe.budgetHit ? null : probe;
    }

    /**
     * Whether {@code steps} replay from {@code held} under their own recorded layouts: every craft's
     * items must be in stock, and every step is credited its result and the remainders of what it
     * consumed (the same netting rule {@link PlanSimulator#run} applies). The plan already fixed
     * which item each slot takes, so this is a count check, not a {@link SlotAssignment} replay.
     */
    private boolean replays(List<Step<R, K>> steps, Map<K, Long> held) {
        UndoMap<K, Long> inv = new UndoMap<>(held);
        for (Step<R, K> step : steps) {
            for (SlotLayout<K> run : step.runs) {
                if (!applyRun(step.recipe, run, inv)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Applies one run of crafts of {@code recipe} to {@code inv} under run's fixed layout, or
     * returns false (nothing applied) when a craft would come up short. Every craft of the run uses
     * and is credited the same amounts, so an item's stock moves linearly craft by craft and only
     * the first or the last craft of the run can be the binding one — a closed-form check, exact
     * even for a run that feeds itself (its result or a remainder among its own layout items),
     * without walking the crafts one at a time. Use and credit are netted per item before applying,
     * so an item a craft both consumes and is credited is not inflated (the same rule
     * {@link PlanSimulator#run} nets by).
     */
    private boolean applyRun(RecipeInfo<R, K> recipe, SlotLayout<K> run, UndoMap<K, Long> inv) {
        Map<K, Long> use = new LinkedHashMap<>();
        for (K item : run.items()) {
            use.merge(item, 1L, Long::sum);
        }
        Map<K, Long> credit = new LinkedHashMap<>();
        if (recipe.result() != null && recipe.resultCount() > 0) {
            credit.merge(recipe.result(), (long) recipe.resultCount(), Long::sum);
        }
        if (remainder != null) {
            for (Map.Entry<K, Long> entry : use.entrySet()) {
                K leftBehind = remainder.apply(entry.getKey());
                if (leftBehind != null) {
                    credit.merge(leftBehind, entry.getValue(), Long::sum);
                }
            }
        }
        Set<K> items = new LinkedHashSet<>(use.keySet());
        items.addAll(credit.keySet());
        long crafts = run.crafts();
        for (K item : items) {
            long perUse = use.getOrDefault(item, 0L);
            long perCredit = credit.getOrDefault(item, 0L);
            long have = count(inv, item);
            long net = perCredit - perUse; // stock change per craft (negative: consumed)
            if (net >= 0) {
                if (have < perUse) { // stock only grows: the first craft is the binding one
                    return false;
                }
            } else if (have - satMul(crafts - 1, perUse - perCredit) < perUse) {
                // Stock falls every craft: the last craft is the binding one.
                return false;
            }
        }
        for (K item : items) {
            long net = credit.getOrDefault(item, 0L) - use.getOrDefault(item, 0L);
            if (net == 0) {
                continue;
            }
            long have = count(inv, item);
            long after = net > 0
                    ? satAdd(have, satMul(crafts, net))
                    : have - satMul(crafts, -net); // the check above keeps this non-negative
            if (after <= 0) {
                inv.remove(item);
            } else {
                inv.put(item, after);
            }
        }
        return true;
    }

    /**
     * One planned batch, with the recipe itself (for replay), a long count (uncapped passes) and the
     * per-slot item runs the pass that built the step decided (never empty when the step was
     * recorded: even a recipe whose slots all repeat one item holds a single run).
     */
    private static final class Step<R, K> {
        final RecipeInfo<R, K> recipe;
        final long crafts;
        final List<SlotLayout<K>> runs;

        Step(RecipeInfo<R, K> recipe, long crafts, List<SlotLayout<K>> runs) {
            // Success accepts an empty list as "no layout"; a solver step must never produce one, or
            // its consumption would go unchecked in replays().
            if (runs.isEmpty()) {
                throw new IllegalStateException("a recorded step has no layout runs");
            }
            this.recipe = recipe;
            this.crafts = crafts;
            this.runs = runs;
        }
    }

    /** A plan's steps, or (steps null) why there is none. */
    private static final class Plan<R, K> {
        final List<Step<R, K>> steps;
        final Set<K> missing;
        final boolean budgetExceeded;
        final boolean tooLarge;

        Plan(List<Step<R, K>> steps, Set<K> missing, boolean budgetExceeded) {
            this(steps, missing, budgetExceeded, false);
        }

        Plan(List<Step<R, K>> steps, Set<K> missing, boolean budgetExceeded, boolean tooLarge) {
            this.tooLarge = tooLarge;
            this.steps = steps;
            this.missing = missing;
            this.budgetExceeded = budgetExceeded;
        }
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
     * {@link Result.Failure#missing}). The walk credits whatever can be crafted from stock (the same
     * batched search {@link #solve} uses) and commits to the first producer per item that does not
     * loop back through a craft cycle for the rest.
     * Its list is then checked against the planner itself: if the target is already solvable the
     * answer is empty; if holding the listed items would still not make it solvable, up to
     * {@value #TOP_UP_ROUNDS} further walks over the topped-up inventory add what the planner's own
     * choices consume; if that still does not verify (or the walk lists nothing for a target the
     * planner cannot make), the first list is returned flagged
     * {@link ShortfallResult#approximate()}. Crafting remainders are intentionally ignored by the
     * walk: a byproduct (an empty bucket) is never a gatherable input.
     */
    public ShortfallResult<K> shortfall(RecipeInfo<R, K> target, Map<K, Integer> inventory) {
        Map<K, Long> held = toLong(inventory);
        Walk<K> walk = walk(target, held);
        Map<K, Long> demands = walk.demands;
        Map<K, List<K>> alternatives = walk.alternatives;
        boolean approximate = walk.approximate;
        boolean saturated = demands.containsValue(Long.MAX_VALUE);
        if (!approximate && demands.isEmpty() && !feasible(target, held)) {
            // The walk thinks stock covers everything but the planner disagrees: nothing better to
            // list, so say the empty answer is not to be trusted.
            approximate = true;
        } else if (!approximate && !saturated && !demands.isEmpty()) {
            if (feasible(target, held)) {
                // The walk's per-slot order can miss a plan the planner finds: nothing to gather.
                demands = new HashMap<>();
                alternatives = new HashMap<>();
            } else {
                Map<K, Long> total = new HashMap<>(demands);
                Map<K, List<K>> totalAlternatives = copyAlternatives(alternatives);
                boolean verified = false;
                for (int round = 0; ; round++) {
                    Map<K, Long> gathered = plus(held, total);
                    if (feasible(target, gathered)) {
                        verified = true;
                        break;
                    }
                    if (round == TOP_UP_ROUNDS) {
                        break;
                    }
                    Walk<K> more = walk(target, gathered);
                    if (more.approximate || more.demands.isEmpty()
                            || more.demands.containsValue(Long.MAX_VALUE)) {
                        break;
                    }
                    for (Map.Entry<K, Long> entry : more.demands.entrySet()) {
                        total.merge(entry.getKey(), entry.getValue(), RecipeGraphSolver::satAdd);
                    }
                    for (Map.Entry<K, List<K>> entry : more.alternatives.entrySet()) {
                        union(totalAlternatives, entry.getKey(), entry.getValue());
                    }
                }
                if (verified) {
                    demands = total;
                    alternatives = totalAlternatives;
                } else {
                    approximate = true; // the planner may need more than this list
                }
            }
        }
        // Re-read: the top-up merges can saturate a count the first walk left exact.
        saturated = demands.containsValue(Long.MAX_VALUE);
        return new ShortfallResult<>(demands, alternatives, approximate, saturated);
    }

    /** One shortfall walk over {@code held}: leaf demands, their alternatives, and the caveat flag. */
    private Walk<K> walk(RecipeInfo<R, K> target, Map<K, Long> held) {
        Solve solve = new Solve(true, false, false, false);
        solve.virtual = new UndoMap<>(held);
        solve.shortfall = new UndoMap<>(new HashMap<K, Long>());
        solve.alts = new UndoMap<>(new HashMap<K, List<K>>());
        for (Group<K> group : solve.groups(target)) {
            // Nothing is mid-craft at the top, so no cycle hit can come back here (every hit set is a
            // subset of the chain being crafted).
            solve.requireSlot(group, group.count, new HashSet<K>(), 0);
        }
        return new Walk<>(solve.shortfall.copy(), solve.alts.copy(), solve.approximate || solve.budgetHit);
    }

    /**
     * Whether {@link #solve} would succeed holding {@code held} (long counts): the same validated
     * {@code plan} path, with the same {@link Integer#MAX_VALUE} per-step batch cap, so a gather list is
     * never verified by a plan {@link #solve} could not emit.
     */
    private boolean feasible(RecipeInfo<R, K> target, Map<K, Long> held) {
        return plan(target, held, true).steps != null;
    }

    private static final class Walk<T> {
        final Map<T, Long> demands;
        final Map<T, List<T>> alternatives;
        final boolean approximate;

        Walk(Map<T, Long> demands, Map<T, List<T>> alternatives, boolean approximate) {
            this.demands = demands;
            this.alternatives = alternatives;
            this.approximate = approximate;
        }
    }

    /**
     * One ingredient slot of a recipe (or a set of identical ones) and how many it stands for.
     * {@link #slotIndexes} maps the group back to its slots' positions in the recipe's non-empty
     * slot order (what {@link PlanSimulator#nonEmptySlots} returns), so a group's consumption can
     * be laid out per craft and per slot.
     */
    private static final class Group<T> {
        final List<T> alternatives;
        final List<T> craftable;
        final List<Integer> slotIndexes = new ArrayList<>();
        long count;

        Group(List<T> alternatives, List<T> craftable) {
            this.alternatives = alternatives;
            this.craftable = craftable;
        }
    }

    /**
     * Mutable per-pass state: the search budget, failure diagnostics, and (for a shortfall walk) the
     * walk's journaled maps.
     */
    private final class Solve {
        /** Merge identical slots of a recipe into one demand (false: one group per slot). */
        final boolean batched;
        /** False for the shortfall walk, which ignores byproducts (see #shortfall). */
        final boolean creditRemainders;
        /** Plan every step by replaying it against the stock as it stands (see #plan, pass 3). */
        final boolean exact;
        /** Cap a recorded batch at Integer.MAX_VALUE crafts (a PlannedStep's int). */
        final boolean capInt;
        /**
         * Set when a slot group came up short on an item a different group had taken this pass: the
         * failure may be an artifact of which group took it first, so the exact pass is worth a try.
         */
        boolean contention = false;
        /** A {@link #contention} in which the taker or the short group accepts more than one item. */
        boolean choiceContention = false;
        /** The first group that took each item this pass (a hint only; never rolled back). */
        private final Map<K, Group<K>> firstTaker = new HashMap<>();
        /** Resolver (craft) node visits left; shared by the plan search and the walk's stock search. */
        int attemptsLeft = maxAttempts;
        /** Shortfall expansion (require) node visits left. */
        int expandLeft = maxAttempts;
        boolean budgetHit = false;
        /**
         * The quantity path's passes (see #planByQuantity): a kept step costs no attempt (a step
         * discarded later is charged then), and a batch that shrank on a shortage without freeing
         * a remainder is not asked for again.
         */
        boolean quantityFree = false;
        /** Do not ask a candidate again after a batch that shrank on a shortage and freed nothing. */
        boolean skipFutileReasks = false;
        /** The recipes this pass may craft (identity), or null for every producer. */
        Set<RecipeInfo<R, K>> allowed;
        /** Every recipe whose batch is in the current result, in order (probe only; else null). */
        List<RecipeInfo<R, K>> used;
        /** Set when a recorded batch was cut down to {@link Integer#MAX_VALUE} crafts. */
        boolean clipped = false;
        /** Set when the recorded plan reached {@code maxSteps}. */
        boolean tooLong = false;
        /** The batch {@link #craft} last returned shrank on a shortage and freed no remainder. */
        private boolean exhausted = false;
        final Set<K> missing = new LinkedHashSet<>();
        /**
         * Set by the shortfall walk when the depth/expansion budget forced an early leaf in a branch
         * that was kept; undoing a branch restores it. ({@link #budgetHit} stays sticky: the stock
         * search budget is not refunded.)
         */
        boolean approximate = false;
        private final Map<RecipeInfo<R, K>, List<Group<K>>> groupCache = new IdentityHashMap<>();

        // Shortfall walk state (null during a plain solve).
        UndoMap<K, Long> virtual;
        UndoMap<K, Long> shortfall;
        UndoMap<K, List<K>> alts;

        Solve(boolean batched, boolean creditRemainders, boolean exact, boolean capInt) {
            this.batched = batched;
            this.creditRemainders = creditRemainders;
            this.exact = exact;
            this.capInt = capInt;
        }

        /** Undoes the steps (and probe trace) recorded since the marks; see {@link #quantityFree}. */
        private void discard(List<Step<R, K>> steps, int stepMark, int usedMark) {
            if (steps != null) {
                if (quantityFree) {
                    attemptsLeft -= steps.size() - stepMark;
                }
                steps.subList(stepMark, steps.size()).clear();
            }
            if (used != null) {
                used.subList(usedMark, used.size()).clear();
            }
        }

        private boolean visit() {
            if (attemptsLeft <= 0) {
                budgetHit = true;
                return false;
            }
            attemptsLeft--;
            return true;
        }

        /**
         * Non-empty slots of {@code recipe} in slot order. A batched pass merges identical slots
         * into their first occurrence (nine compression slots = one demand for nine), which keeps
         * a deep tree to one visit per recipe; a unitwise pass keeps one group per slot.
         */
        List<Group<K>> groups(RecipeInfo<R, K> recipe) {
            List<Group<K>> cached = groupCache.get(recipe);
            if (cached != null) {
                return cached;
            }
            List<List<K>> slots = recipe.ingredientSlots();
            List<List<K>> craftableSlots = recipe.craftableIngredientSlots();
            List<Group<K>> groups = new ArrayList<>();
            Map<List<List<K>>, Group<K>> byKey = new HashMap<>();
            int nonEmpty = 0;
            for (int s = 0; s < slots.size(); s++) {
                List<K> slot = slots.get(s);
                if (slot.isEmpty()) {
                    continue;
                }
                // Aligned 1:1 by contract; fall back to the full slot if an adapter under-supplies.
                List<K> craftable = s < craftableSlots.size() ? craftableSlots.get(s) : slot;
                Group<K> group = null;
                List<List<K>> key = null;
                if (batched) {
                    key = Arrays.asList(slot, craftable);
                    group = byKey.get(key);
                }
                if (group == null) {
                    group = new Group<>(slot, craftable);
                    groups.add(group);
                    if (batched) {
                        byKey.put(key, group);
                    }
                }
                group.count++;
                group.slotIndexes.add(nonEmpty++);
            }
            groupCache.put(recipe, groups);
            return groups;
        }

        /**
         * Performs up to {@code want} crafts of {@code recipe}: satisfies every slot group for the
         * batch (consuming from {@code inv}, possibly emitting sub-craft steps), then emits one step
         * for the batch and credits its result plus the remainders of everything it consumed.
         *
         * <p>A batch that comes up short is undone and retried smaller: first at the count the short
         * group did supply (exact when nothing before it competed), then — if an earlier group may
         * have taken what the short one needed — at a single craft, the per-unit planner's step.
         * The caller asks again for whatever is still missing.
         *
         * @param steps the plan to append to, or null to search without recording (no int cap)
         * @return crafts actually applied (0 leaves {@code inv}/{@code steps} untouched)
         */
        long craft(RecipeInfo<R, K> recipe,
                   long want,
                   UndoMap<K, Long> inv,
                   List<Step<R, K>> steps,
                   Set<K> crafting,
                   int depth) {
            // A PlannedStep holds an int: a larger batch is not executable, so it is capped here and
            // the shortfall surfaces as a clean failure upstream.
            long size = capInt && steps != null ? Math.min(want, Integer.MAX_VALUE) : want;
            if (size <= 0) {
                return 0;
            }
            clipped |= size < want;
            exhausted = false; // only the batched path below sets it; never leave a stale value
            if (exact) {
                return craftExact(recipe, size, inv, steps, crafting, depth);
            }
            List<Group<K>> groups = groups(recipe);
            boolean contended = false;
            boolean triedSingle = false;
            boolean shrunk = false;
            while (true) {
                if (depth > maxDepth || !visit()) {
                    return 0;
                }
                int invMark = inv.mark();
                int stepMark = steps == null ? 0 : steps.size();
                int usedMark = used == null ? 0 : used.size();
                Map<K, Long> consumed = new LinkedHashMap<>();
                // What each group took, per item: the batched/unitwise pass's own per-slot choice,
                // recorded per craft as the step's layout (merged {@code consumed} still drives
                // remainder crediting, where groups sharing an item must be summed).
                List<Map<K, Long>> groupUse = new ArrayList<>(Collections.<Map<K, Long>>nCopies(
                        groups.size(), null));
                for (int i = 0; i < groups.size(); i++) {
                    groupUse.set(i, new LinkedHashMap<K, Long>());
                }
                int shortGroup = -1;
                long supplied = 0;
                for (int i = 0; i < groups.size(); i++) {
                    Group<K> group = groups.get(i);
                    long need = satMul(size, group.count);
                    long got = satisfySlot(group, need, inv, steps, crafting, depth, consumed, groupUse.get(i));
                    if (got < need) {
                        missing.add(group.alternatives.get(0));
                        for (K item : group.alternatives) {
                            Group<K> taker = firstTaker.get(item);
                            if (taker != null && taker != group) {
                                contention = true;
                                if (taker.alternatives.size() > 1 || group.alternatives.size() > 1) {
                                    choiceContention = true;
                                }
                            }
                        }
                        shortGroup = i;
                        supplied = got / group.count;
                        break;
                    }
                }
                if (shortGroup < 0 && quantityFree && steps != null && steps.size() >= maxSteps) {
                    tooLong = true;
                    budgetHit = true;
                    inv.rollback(invMark);
                    discard(steps, stepMark, usedMark);
                    return 0;
                }
                if (shortGroup < 0) {
                    if (steps != null) {
                        steps.add(new Step<>(recipe, size, layoutRuns(size, groups, groupUse)));
                        if (quantityFree) {
                            attemptsLeft++;
                        }
                    }
                    if (used != null) {
                        used.add(recipe);
                    }
                    addTo(inv, recipe.result(), satMul(size, recipe.resultCount()));
                    // Remainders of consumed items physically reappear when this step executes.
                    boolean freed = false;
                    if (creditRemainders) {
                        for (Map.Entry<K, Long> entry : consumed.entrySet()) {
                            K leftBehind = remainder.apply(entry.getKey());
                            if (leftBehind != null) {
                                addTo(inv, leftBehind, entry.getValue());
                                freed = true;
                            }
                        }
                    }
                    exhausted = shrunk && !freed;
                    return size;
                }
                inv.rollback(invMark);
                discard(steps, stepMark, usedMark);
                if (shortGroup > 0) {
                    contended = true;
                }
                if (budgetHit) {
                    return 0;
                }
                shrunk = true;
                if (supplied > 0) {
                    size = supplied; // strictly smaller: got < need
                } else if (contended && size > 1 && !triedSingle) {
                    size = 1;
                    triedSingle = true;
                } else {
                    return 0;
                }
            }
        }

        /**
         * Gathers up to {@code need} units for one slot group: stock across the alternatives in
         * preference order, then crafts the rest from the craftable alternatives in order.
         *
         * @param use this group's own consumption, per item (the step's layout); {@code consumed} is
         *            the merged cross-group total that drives remainder crediting
         * @return units consumed (recorded in {@code consumed} for remainder crediting)
         */
        private long satisfySlot(Group<K> group,
                                 long need,
                                 UndoMap<K, Long> inv,
                                 List<Step<R, K>> steps,
                                 Set<K> crafting,
                                 int depth,
                                 Map<K, Long> consumed,
                                 Map<K, Long> use) {
            long got = 0;
            for (K item : group.alternatives) {
                if (got >= need) {
                    return got;
                }
                long n = take(item, need - got, inv, consumed);
                if (n > 0) {
                    use.merge(item, n, RecipeGraphSolver::satAdd);
                }
                got += taken(group, item, n);
            }
            // Only craft an alternative whose crafted (bare) form would actually satisfy the slot:
            // a filled-bucket alternative is matchable from stock above but never manufactured here
            // (crafting the bucket yields it empty, which the ingredient rejects).
            for (K item : group.craftable) {
                if (got >= need || budgetHit) {
                    break;
                }
                if (produce(item, need - got, inv, steps, crafting, depth + 1) > 0) {
                    long n = take(item, need - got, inv, consumed);
                    if (n > 0) {
                        use.merge(item, n, RecipeGraphSolver::satAdd);
                    }
                    got += taken(group, item, n);
                }
            }
            return got;
        }

        /** Notes {@code group} as a taker of {@code item} (see {@link #contention}); returns {@code n}. */
        private long taken(Group<K> group, K item, long n) {
            if (n > 0 && !firstTaker.containsKey(item)) {
                firstTaker.put(item, group);
            }
            return n;
        }

        /**
         * The exact pass's {@link #craft}: a batch is accepted only when {@link PlanSimulator#run}
         * fills every slot of every craft from the stock as it stands now, and that replay IS the
         * consumption recorded (no group reserves stock ahead of the sub-crafts that run before the
         * step). While the replay is short, the missing units of each short slot kind are sub-crafted
         * (each of those crafts replayed the same way, so they physically take what the rule makes
         * them take) and the batch is replayed again, up to {@link #EXACT_ROUNDS} times. A batch that
         * still comes up short is undone and retried at the crafts that did complete, then at one.
         */
        private long craftExact(RecipeInfo<R, K> recipe,
                                long size,
                                UndoMap<K, Long> inv,
                                List<Step<R, K>> steps,
                                Set<K> crafting,
                                int depth) {
            List<List<K>> craftableSlots = recipe.craftableIngredientSlots();
            boolean triedSingle = false;
            while (true) {
                if (depth > maxDepth || !visit()) {
                    return 0;
                }
                int invMark = inv.mark();
                int stepMark = steps == null ? 0 : steps.size();
                long complete = 0;
                boolean done = false;
                List<PlanSimulator.Chunk<K>> chunks = null;
                for (int round = 0; ; round++) {
                    int replayMark = inv.mark();
                    chunks = new ArrayList<>();
                    PlanSimulator.Batch<K> replay = PlanSimulator.run(recipe, size, inv, remainder, false, chunks);
                    if (!replay.isShort()) {
                        done = true;
                        break;
                    }
                    complete = replay.completeBeforeShort;
                    inv.rollback(replayMark);
                    if (round == EXACT_ROUNDS || budgetHit) {
                        break;
                    }
                    if (!subCraftShortages(replay, craftable(recipe, craftableSlots), inv, steps, crafting, depth)) {
                        break;
                    }
                }
                if (done) {
                    if (steps != null) {
                        // The chunks ARE the layout: each is a run of crafts SlotAssignment laid out
                        // identically, so the plan carries the choice the exact pass made.
                        steps.add(new Step<>(recipe, size, chunkRuns(chunks)));
                    }
                    return size;
                }
                inv.rollback(invMark);
                if (steps != null) {
                    steps.subList(stepMark, steps.size()).clear();
                }
                if (budgetHit) {
                    return 0;
                }
                if (complete > 0 && complete < size) {
                    size = complete;
                } else if (size > 1 && !triedSingle) {
                    size = 1;
                    triedSingle = true;
                } else {
                    return 0;
                }
            }
        }

        /**
         * The craftable subset for each of the recipe's non-empty slots (aligned with
         * {@link PlanSimulator#nonEmptySlots}); the full slot where the adapter under-supplies.
         */
        private List<List<K>> craftable(RecipeInfo<R, K> recipe, List<List<K>> craftableSlots) {
            List<List<K>> slots = recipe.ingredientSlots();
            List<List<K>> out = new ArrayList<>();
            for (int s = 0; s < slots.size(); s++) {
                if (!slots.get(s).isEmpty()) {
                    out.add(s < craftableSlots.size() ? craftableSlots.get(s) : slots.get(s));
                }
            }
            return out;
        }

        /**
         * Sub-crafts what a short replay lacked: per distinct slot kind (identical slots pooled), its
         * missing units from the craftable alternatives in preference order.
         *
         * @return whether anything was produced at all
         */
        private boolean subCraftShortages(PlanSimulator.Batch<K> replay,
                                          List<List<K>> craftable,
                                          UndoMap<K, Long> inv,
                                          List<Step<R, K>> steps,
                                          Set<K> crafting,
                                          int depth) {
            Map<List<List<K>>, Long> deficit = new LinkedHashMap<>();
            for (int s = 0; s < replay.slots.size(); s++) {
                if (replay.shortage[s] > 0) {
                    deficit.merge(Arrays.asList(replay.slots.get(s), craftable.get(s)), replay.shortage[s],
                            RecipeGraphSolver::satAdd);
                }
            }
            boolean progress = false;
            for (Map.Entry<List<List<K>>, Long> entry : deficit.entrySet()) {
                long need = entry.getValue();
                for (K item : entry.getKey().get(1)) {
                    if (need <= 0 || budgetHit) {
                        break;
                    }
                    long got = produce(item, need, inv, steps, crafting, depth + 1);
                    if (got > 0) {
                        progress = true;
                        need -= Math.min(need, got);
                    }
                }
                if (need > 0) {
                    missing.add(entry.getKey().get(0).get(0));
                }
            }
            return progress;
        }

        /**
         * Crafts up to {@code units} of {@code item} (beyond stock) through its producers in order.
         * Each producer is asked again for whatever is still missing until it supplies nothing
         * (a remainder freed by one batch can feed the next — fill bucket, use, refill), then the
         * next producer covers the rest.
         *
         * <p>Progress is the NET gain of {@code item} in {@code inv}, not the yield: a sub-craft may
         * itself consume {@code item} from stock (a generating cycle such as one i1 → three i0, each
         * i0 → one i1). A craft that gains nothing is undone, so the loop always terminates.
         *
         * @return net units added to {@code inv} (may exceed {@code units} by the last craft's surplus)
         */
        long produce(K item,
                     long units,
                     UndoMap<K, Long> inv,
                     List<Step<R, K>> steps,
                     Set<K> crafting,
                     int depth) {
            if (units <= 0 || crafting.contains(item)) {
                return 0;
            }
            long start = count(inv, item);
            long produced = 0;
            crafting.add(item);
            try {
                for (RecipeInfo<R, K> candidate : producers.apply(item)) {
                    int yield = candidate.resultCount();
                    if (yield <= 0 || !Objects.equals(candidate.result(), item)
                            || (allowed != null && !allowed.contains(candidate))) {
                        continue;
                    }
                    while (produced < units && !budgetHit) {
                        int invMark = inv.mark();
                        int stepMark = steps == null ? 0 : steps.size();
                        int usedMark = used == null ? 0 : used.size();
                        long done = craft(candidate, ceilDiv(units - produced, yield), inv, steps,
                                crafting, depth);
                        if (done <= 0) {
                            break;
                        }
                        // Read now: a later craft overwrites it. Chasing a short batch's leftovers
                        // at every level is what exhausts the budget, so the second probe stops
                        // here. A heuristic: it can call a tree short that the leftovers would have
                        // completed, and only answers where the full probe ran out of budget.
                        boolean spent = skipFutileReasks && exhausted;
                        long net = count(inv, item) - start;
                        if (net <= produced) {
                            inv.rollback(invMark);
                            discard(steps, stepMark, usedMark);
                            break;
                        }
                        produced = net;
                        if (spent) {
                            break;
                        }
                    }
                    if (produced >= units || budgetHit) {
                        break;
                    }
                }
            } finally {
                crafting.remove(item);
            }
            return produced;
        }

        // --- Shortfall walk (see RecipeGraphSolver#shortfall) -----------------------------------

        private boolean expandVisit() {
            if (expandLeft <= 0) {
                return false;
            }
            expandLeft--;
            return true;
        }

        /**
         * Demands {@code qty} units for one slot group, remembering its full alternative set.
         * Order of preference for a gather list: stock you already have, then a raw leaf (an
         * alternative with no recipe — a true gatherable), then whatever can be crafted entirely
         * from stock, and only then crafting the first craftable alternative from scratch. This
         * matters for tag slots like {@code #logs}, which accept both the raw {@code oak_log} and
         * the craftable {@code oak_wood} (4 logs → 3 wood): gathering the log is right; "crafting"
         * the wood block would demand four logs per slot. And for {@code #planks} with one oak log
         * and one birch log held, both are used before anything is listed to gather.
         *
         * <p>A craftable alternative whose expansion cycles back into the chain (see {@link #require})
         * is undone and the next one tried. If every craftable alternative is already being crafted
         * up the chain or cycles, nothing is recorded: the cycle members hit are returned so the
         * ancestor that owns them can try another producer.
         *
         * @return the items of {@code crafting} this slot could only be satisfied through (empty: done)
         */
        Set<K> requireSlot(Group<K> group, long qty, Set<K> crafting, int depth) {
            long left = qty;
            for (K item : group.alternatives) {
                if (left <= 0) {
                    return Collections.emptySet();
                }
                left -= take(item, left, virtual, null);
            }
            if (left <= 0) {
                return Collections.emptySet();
            }
            for (K item : group.alternatives) {
                if (producers.apply(item).isEmpty()) {
                    require(item, left, group.alternatives, crafting, depth);
                    return Collections.emptySet(); // a raw gatherable — the simplest answer
                }
            }
            // Credit everything the player can already make (quantity-exact, the solve's own batched
            // search, recording no steps). Remainders are off for this walk, so it never assumes a
            // byproduct.
            for (K item : group.craftable) {
                if (left <= 0 || budgetHit) {
                    break;
                }
                if (produce(item, left, virtual, null, crafting, depth + 1) > 0) {
                    left -= take(item, left, virtual, null);
                }
            }
            if (left <= 0) {
                return Collections.emptySet();
            }
            Set<K> hits = null;
            for (K item : group.craftable) {
                if (crafting.contains(item)) {
                    // Mid-craft up the chain: only reachable through that ancestor's own recipe.
                    hits = withAll(hits, Collections.singleton(item));
                    continue;
                }
                int virtualMark = virtual.mark();
                int shortfallMark = shortfall.mark();
                int altsMark = alts.mark();
                boolean approximateMark = approximate;
                Set<K> itemHits = require(item, left, group.alternatives, crafting, depth);
                if (itemHits.isEmpty()) {
                    return itemHits; // crafted (or listed) without looping back up the chain
                }
                virtual.rollback(virtualMark);
                shortfall.rollback(shortfallMark);
                alts.rollback(altsMark);
                approximate = approximateMark; // a depth/budget leaf in an undone branch is gone too
                hits = withAll(hits, itemHits);
            }
            if (hits != null) {
                return hits; // every way in here is a cycle: an ancestor decides
            }
            // Nothing here can be manufactured (an inventory-only filled bucket): the item itself is
            // what to gather.
            recordLeaf(group.alternatives.get(0), left, group.alternatives);
            return Collections.emptySet();
        }

        /**
         * Accumulates the leaf demand to obtain {@code qty} of {@code item}: consume stock first,
         * then either sub-craft one batch via a producer (recording the sub-recipe's own leaf
         * demands and crediting the overproduction back) or, if the item has no usable producer,
         * record it as a gatherable.
         *
         * <p>Producers are tried in order, each expansion journaled. One that can only be satisfied
         * through an item already being crafted up the chain (itself or an ancestor) is a cycle —
         * block ⟷ ingot ⟷ nugget, crystal ⟷ crystal_block — so it is undone and the next producer
         * tried; a cycle member that also has a recipe from raws is thus expanded through that
         * recipe. If every producer cycles back to {@code item} itself, crafting it is pointless and
         * it is the gatherable for the original {@code qty} (you'd report "1 coal", not the 9 that
         * filling a block would imply). If some producer cycles back to an ancestor instead, nothing
         * is recorded and those ancestors are returned, so the outermost cycle member — the item
         * actually asked for — is the one listed (1 iron block, not 81 nuggets).
         *
         * @return the items of {@code crafting} that obtaining {@code item} could only go through
         *         (empty: demand recorded)
         */
        Set<K> require(K item, long qty, List<K> slotAlternatives, Set<K> crafting, int depth) {
            if (qty <= 0) {
                return Collections.emptySet();
            }
            qty -= take(item, qty, virtual, null);
            if (qty <= 0) {
                return Collections.emptySet();
            }
            if (crafting.contains(item)) {
                return Collections.singleton(item); // mid-craft up the chain: a cycle
            }
            // Usable producers in order (deterministic — CraftPlanner sorts by id). None at all means
            // it bottoms out as a gatherable.
            boolean expandable = false;
            for (RecipeInfo<R, K> candidate : producers.apply(item)) {
                if (candidate.resultCount() > 0) {
                    expandable = true;
                    break;
                }
            }
            if (!expandable) {
                recordLeaf(item, qty, slotAlternatives); // a true leaf: exact, not approximate
                return Collections.emptySet();
            }
            Set<K> hits = null;
            crafting.add(item);
            try {
                for (RecipeInfo<R, K> producer : producers.apply(item)) {
                    if (producer.resultCount() <= 0) {
                        continue;
                    }
                    if (depth > maxDepth || !expandVisit()) {
                        // Expandable, but out of depth or budget: list the intermediate itself, flagged.
                        recordLeaf(item, qty, slotAlternatives);
                        approximate = true;
                        return Collections.emptySet();
                    }
                    long yield = producer.resultCount();
                    long crafts = ceilDiv(qty, yield);
                    int virtualMark = virtual.mark();
                    int shortfallMark = shortfall.mark();
                    int altsMark = alts.mark();
                    boolean approximateMark = approximate;
                    Set<K> producerHits = null;
                    for (Group<K> group : groups(producer)) {
                        Set<K> groupHits = requireSlot(group, satMul(crafts, group.count), crafting, depth + 1);
                        if (!groupHits.isEmpty()) {
                            producerHits = groupHits;
                            break;
                        }
                    }
                    if (producerHits == null) {
                        long made = satMul(crafts, yield);
                        if (made < Long.MAX_VALUE && made > qty) {
                            addTo(virtual, item, made - qty);
                        }
                        return Collections.emptySet();
                    }
                    // This producer loops back into the chain: undo it and try the next one.
                    virtual.rollback(virtualMark);
                    shortfall.rollback(shortfallMark);
                    alts.rollback(altsMark);
                    // Expansion budget is not refunded, so a later expandable item still flags.
                    approximate = approximateMark;
                    hits = withAll(hits, producerHits);
                }
            } finally {
                crafting.remove(item);
            }
            Set<K> outer = hits == null ? new LinkedHashSet<K>() : new LinkedHashSet<>(hits);
            outer.remove(item);
            if (outer.isEmpty()) {
                // Every producer needs the item itself — a storage cycle. Gather it raw.
                recordLeaf(item, qty, slotAlternatives);
                return Collections.emptySet();
            }
            return outer; // only reachable through an ancestor: that ancestor decides
        }

        /** Records {@code qty} of a gatherable, unioning in the slot's alternatives (for UI cycling). */
        private void recordLeaf(K item, long qty, List<K> slotAlternatives) {
            addTo(shortfall, item, qty);
            List<K> known = alts.get(item);
            List<K> list = known;
            for (K alt : slotAlternatives) {
                if (list == null || !list.contains(alt)) {
                    if (list == known) {
                        list = known == null ? new ArrayList<K>() : new ArrayList<>(known); // copy-on-write
                    }
                    list.add(alt);
                }
            }
            if (list != known) {
                alts.put(item, list);
            }
        }
    }

    /** {@code acc} plus {@code more}, allocating only on first use ({@code acc} may be null). */
    private static <T> Set<T> withAll(Set<T> acc, Set<T> more) {
        Set<T> out = acc == null ? new LinkedHashSet<T>() : acc;
        out.addAll(more);
        return out;
    }

    /** Removes up to {@code max} of {@code item} from {@code inv}; returns how many were taken. */
    private static <T> long take(T item, long max, UndoMap<T, Long> inv, Map<T, Long> consumed) {
        Long have = inv.get(item);
        if (have == null || have <= 0 || max <= 0) {
            return 0;
        }
        long n = Math.min(max, have);
        if (have - n <= 0) {
            inv.remove(item);
        } else {
            inv.put(item, have - n);
        }
        if (consumed != null) {
            consumed.merge(item, n, RecipeGraphSolver::satAdd);
        }
        return n;
    }

    private static <T> long count(UndoMap<T, Long> map, T key) {
        Long have = map.get(key);
        return have == null ? 0 : have;
    }

    private static <T> void addTo(UndoMap<T, Long> map, T key, long n) {
        if (n > 0) {
            map.put(key, satAdd(count(map, key), n));
        }
    }

    /**
     * An int stock count widened for planning. {@link Integer#MAX_VALUE} means "at least that many, count
     * unknown" (the clamp every int snapshot applies, and exactly what an AE2 creative cell or a
     * Sophisticated Storage infinity upgrade reports while delivering any amount), so it is treated as
     * unbounded: {@link Long#MAX_VALUE}, which every count operation here saturates at. A finite source
     * that really holds ~2.1 billion is then over-trusted; execution re-checks every craft against the
     * live source, so such a plan pauses or cancels mid-job instead of being refused up front.
     */
    static long stockCount(int count) {
        return count == Integer.MAX_VALUE ? Long.MAX_VALUE : count;
    }

    private static <T> Map<T, Long> toLong(Map<T, Integer> inventory) {
        Map<T, Long> out = new HashMap<>();
        for (Map.Entry<T, Integer> entry : inventory.entrySet()) {
            Integer count = entry.getValue();
            if (count != null && count > 0) {
                out.put(entry.getKey(), stockCount(count));
            }
        }
        return out;
    }

    private static <T> Map<T, Long> plus(Map<T, Long> a, Map<T, Long> b) {
        Map<T, Long> out = new HashMap<>(a);
        for (Map.Entry<T, Long> entry : b.entrySet()) {
            out.merge(entry.getKey(), entry.getValue(), RecipeGraphSolver::satAdd);
        }
        return out;
    }

    private static <T> void union(Map<T, List<T>> alts, T key, List<T> more) {
        List<T> list = alts.computeIfAbsent(key, k -> new ArrayList<>());
        for (T alt : more) {
            if (!list.contains(alt)) {
                list.add(alt);
            }
        }
    }

    private static <T> Map<T, List<T>> copyAlternatives(Map<T, List<T>> alts) {
        Map<T, List<T>> copy = new HashMap<>();
        for (Map.Entry<T, List<T>> entry : alts.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /** Saturating add of non-negative counts. */
    static long satAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    /** Saturating multiply of non-negative counts. */
    static long satMul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    /** Ceiling division of a non-negative count by a positive divisor, without overflow. */
    static long ceilDiv(long a, long b) {
        return a / b + (a % b == 0 ? 0 : 1);
    }
}
