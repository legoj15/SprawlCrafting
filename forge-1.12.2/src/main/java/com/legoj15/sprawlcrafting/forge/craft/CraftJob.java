package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;

import com.legoj15.solver.DepthFirstSchedule;
import com.legoj15.solver.ProducedLedger;
import com.legoj15.solver.SlotLayout;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

/**
 * An in-progress deferred craft: an ordered list of {@link CraftStep}s ending with the
 * step that produces the requested output. Steps are ordered so that every step's
 * ingredients are either raw resources or outputs of earlier steps (a topological order
 * of the recipe dependency tree, produced by {@code CraftPlanner}).
 *
 * <p>Ingredients are consumed per craft from the live inventory, not reserved upfront. If a
 * step's ingredients are missing when it comes due (player dropped/used them), the job
 * fails gracefully and the player is notified; items already crafted stay in the inventory.
 *
 * <p>Cadence, by step size. A <em>paced</em> step (at most {@link #BULK_THRESHOLD} crafts: every
 * ordinary recipe) keeps the Factorio-style rhythm: one craft per {@link #TICKS_PER_STEP} ticks, each
 * with its own progress update and step sound. A <em>bulk</em> step (more than {@link #BULK_THRESHOLD}
 * crafts, i.e. a batched compressed-block chain such as 6,561 crafts) waits {@link #TICKS_PER_STEP} ticks
 * once, then runs in bursts of as many crafts as fit in
 * {@link #BURST_BUDGET_NANOS} of server time (and all players' bursts together at most
 * {@link #SERVER_TICK_BURST_BUDGET_NANOS}), one burst per tick, until the step is done: roughly 5-16
 * seconds instead of the ~55 minutes one craft per half second would take.
 *
 * <p>A job with any step whose crafts times its per-craft volume (result count or slot count,
 * whichever is bigger) exceeds {@link Integer#MAX_VALUE} — a long step from the solver's quantity
 * path — runs <em>scheduled</em> ({@link DepthFirstSchedule}): the schedule picks the next batch at
 * run time, expanding the plan depth first from the target against a model of the intermediates it
 * itself produced, so no intermediate is ever made much beyond one batch ahead of its consumer. Its
 * batches are capped in items (an int's worth at first) and the cap halves when a batch's output
 * has no room and doubles back after a batch ran whole, so the storage, not a fixed batch size,
 * bounds the intermediates. Every step of such a job bursts as a bulk one; a job whose steps all
 * fit an int behaves exactly as the cadence above describes (the sequential path).
 *
 * <p>Java-8 plain-class port of the modern {@code CraftJob}: {@code List.copyOf} (Java 10)
 * is replaced with {@code Collections.unmodifiableList(new ArrayList<>(...))}; the logic is
 * otherwise identical.
 */
public final class CraftJob {

    /** Half a second between crafts of a paced step, and between steps. */
    public static final int TICKS_PER_STEP = 10;

    /**
     * Largest step that is still paced one craft per {@link #TICKS_PER_STEP}. Ordinary recipes need a
     * handful of crafts per step (even a full stack of sticks is 16), and for those the half-second
     * craft-by-craft rhythm, with its visible progress and sound, is the intended experience. A step
     * with more crafts than this is a batched step from a compression chain, where that rhythm would
     * take minutes to hours, so it runs in bursts instead.
     */
    public static final int BULK_THRESHOLD = 16;

    /**
     * Wall-time budget for one player's burst; the burst stops at whichever of this and
     * the step boundary comes first (always at least one craft; there is no count cap, so this is what
     * bounds a burst). Same value as the modern
     * tree, where a worst-case 64-craft burst of a 9-ingredient recipe measured 2.5-6.1 ms warm and
     * 15.4 ms cold on the 1.21.1 game-test server: 2 ms keeps one player's burst to 4% of a tick.
     * This is the per-job ceiling; {@link #SERVER_TICK_BURST_BUDGET_NANOS} bounds all players together.
     */
    public static final long BURST_BUDGET_NANOS = 2_000_000L;

    /**
     * Wall-time budget that every player's burst shares within one server tick (see {@link BurstBudget}):
     * 10 ms, a fifth of a 50 ms tick, so N players bursting together cost about this instead of N times
     * {@link #BURST_BUDGET_NANOS}. Up to five bulk jobs still get the full per-job 2 ms; beyond that the
     * budget is split evenly. A burst's first craft is exempt (no job starves), so past roughly
     * 100-200 concurrent bulk jobs the one-craft floor, not this figure, sets the cost.
     */
    public static final long SERVER_TICK_BURST_BUDGET_NANOS = 10_000_000L;

    private final ResourceLocation targetRecipe;
    private final ItemStack targetResult;
    private final List<CraftStep> steps;
    private final long totalCrafts;
    private final ProducedLedger<ItemKey, CraftExecutor.LedgerVariant> produced =
            new ProducedLedger<ItemKey, CraftExecutor.LedgerVariant>();
    /**
     * The schedule, decided once here: used when any step's crafts times its per-craft volume exceeds
     * {@link Integer#MAX_VALUE}; null on the sequential path, which keeps the fields below.
     */
    private final DepthFirstSchedule<ItemKey> schedule;
    /** Whether this job runs interleaved; every step-state method below branches on it. */
    private final boolean interleaved;
    /**
     * {@link #currentCraftLayout}'s per-step cursor for an interleaved job (the schedule revisits a
     * step batch by batch): each step's run and that run's first craft against that step's own done
     * count ({@link DepthFirstSchedule#done}). A run start of -1 marks a step never visited.
     */
    private final int[] layoutRunByStep;
    private final long[] layoutRunStartByStep;

    private int stepIndex;
    private long craftsDoneInStep;
    /** {@link #currentCraftLayout}'s cursor: the step it is for, its run, and that run's first craft. */
    private int layoutCursorStep = -1;
    private int layoutRun;
    private long layoutRunStart;
    /** Crafts of all steps before {@link #stepIndex}, so {@link #craftsDone()} is O(1). */
    private long craftsBeforeStep;
    private int progressTicks;
    private int ticksSinceSync;
    private int ticksSinceReachCheck = TICKS_PER_STEP;
    /** The crafting table the last passing reach scan found; the cheap per-tick re-check watches it. */
    private BlockPos reachTable;
    /**
     * True once this job relies on a connected station inventory (a Crafting Station's chest): it was
     * planned with one in the pool, or a craft has since consumed from it. When set, a step that can't
     * find an ingredient while no station chest is open PAUSES (the materials may be in the unseen
     * chest) instead of cancelling — see {@code CraftQueueManager}. Kept apart from
     * {@link #networkDependent} so each source is judged by its own visibility.
     */
    private boolean chestDependent;
    /**
     * Same for an open network crafting terminal's network (AE2): planned with it in the pool, or a
     * craft has since consumed from it or stored overflow in it. A job started with the terminal
     * closed can still become dependent mid-job, the first time a craft draws network stock or
     * parks an item there.
     */
    private boolean networkDependent;
    /**
     * Whether this job's plan counted the player's protected stacks (enchanted and/or renamed,
     * never damaged; {@code CraftExecutor#isProtected}) as material — set by the caller that
     * planned it that way (issue #40), after the plain pool fell short and the player confirmed.
     * Such a job may pick protected stacks from the 36 main slots and, while a network crafting
     * terminal is open, from the network behind it (issue #49), always after every plain stack
     * in the pool; every other job keeps the plain rule exactly. Damaged stacks are never
     * protected material, on any job.
     */
    private boolean usesProtected;

    /**
     * @param held the planning inventory snapshot the job was planned against (missing = 0,
     *             {@link Long#MAX_VALUE} = unbounded): the seed of the interleaved path's stock model
     */
    public CraftJob(ResourceLocation targetRecipe, ItemStack targetResult, List<CraftStep> steps,
                    Map<ItemKey, Long> held) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a craft job needs at least one step");
        }
        this.targetRecipe = targetRecipe;
        this.targetResult = targetResult.copy();
        this.steps = Collections.unmodifiableList(new ArrayList<CraftStep>(steps));
        long sum = 0;
        long[] totals = new long[steps.size()];
        List<List<SlotLayout<ItemKey>>> layouts = new ArrayList<List<SlotLayout<ItemKey>>>(steps.size());
        List<ItemKey> results = new ArrayList<ItemKey>(steps.size());
        int[] resultCounts = new int[steps.size()];
        boolean overInt = false;
        for (int i = 0; i < totals.length; i++) {
            CraftStep step = this.steps.get(i);
            totals[i] = step.crafts();
            sum = saturatingAdd(sum, totals[i]);
            layouts.add(step.layouts());
            results.add(step.result());
            resultCounts[i] = step.resultCount();
            overInt |= saturating(totals[i], Math.max(step.resultCount(), step.slotCount())) > Integer.MAX_VALUE;
        }
        this.totalCrafts = sum;
        this.interleaved = overInt;
        this.schedule = interleaved
                ? new DepthFirstSchedule<ItemKey>(totals, layouts, results, resultCounts, held)
                : null;
        if (interleaved) {
            LogManager.getLogger("SprawlCrafting").info(
                    "SprawlCrafting: {} runs on a depth-first schedule:{}", targetRecipe, scheduleReport());
            this.layoutRunByStep = new int[steps.size()];
            this.layoutRunStartByStep = new long[steps.size()];
            Arrays.fill(layoutRunStartByStep, -1L);
        } else {
            this.layoutRunByStep = null;
            this.layoutRunStartByStep = null;
        }
    }

    /**
     * Diagnostic text for an interleaved job's log lines: one line per step with its recipe, crafts done of
     * its total, result and the schedule's model stock of that result, then the current batch and cap.
     */
    public String scheduleReport() {
        if (!interleaved) {
            return " (sequential)";
        }
        // Choose first: choosing can skip surplus steps, and the lines below should show that.
        int current = schedule.current();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            CraftStep step = steps.get(i);
            text.append("\n  ").append(i).append(' ').append(step.recipeId()).append(": ")
                    .append(schedule.done(i)).append('/').append(step.crafts()).append(" -> ")
                    .append(step.resultCount()).append("x ").append(step.result())
                    .append(", model stock ").append(step.result() == null ? -1 : schedule.stock(step.result()));
        }
        text.append("\n  current step ").append(current).append(", batch ")
                .append(current < 0 ? 0 : schedule.batch()).append(", cap ").append(schedule.cap());
        return text.toString();
    }

    /** {@code a * b} for non-negative counts, pinned at {@link Long#MAX_VALUE} instead of wrapping. */
    private static long saturating(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    /** {@code a + b} for non-negative counts, pinned at {@link Long#MAX_VALUE} instead of wrapping. */
    public static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public ResourceLocation targetRecipe() {
        return targetRecipe;
    }

    public boolean chestDependent() {
        return chestDependent;
    }

    public void setChestDependent(boolean chestDependent) {
        this.chestDependent = chestDependent;
    }

    public boolean networkDependent() {
        return networkDependent;
    }

    public void setNetworkDependent(boolean networkDependent) {
        this.networkDependent = networkDependent;
    }

    /** See {@link #usesProtected}. */
    public boolean usesProtected() {
        return usesProtected;
    }

    public void setUsesProtected(boolean usesProtected) {
        this.usesProtected = usesProtected;
    }

    /** Whether the job relies on any station-side source, i.e. its pauses point the player back to a station. */
    public boolean stationDependent() {
        return chestDependent || networkDependent;
    }

    /** The final output, for chat/HUD display. Do not mutate. */
    public ItemStack targetResult() {
        return targetResult;
    }

    /** What this job crafted and has not used back up; lets the executor take its own altered intermediates. */
    public ProducedLedger<ItemKey, CraftExecutor.LedgerVariant> produced() {
        return produced;
    }

    public List<CraftStep> steps() {
        return steps;
    }

    /** The step now due: the schedule's current step when interleaved, else the sequential cursor. */
    public CraftStep currentStep() {
        return steps.get(interleaved ? schedule.current() : stepIndex);
    }

    /**
     * The plan's per-slot items for the craft about to run — the layout run covering the current
     * step's done count — or null when the step carries no layout (a step built without one, or the
     * counter past the last run: defensive, the step advances with the last craft) or the job is
     * finished: the executor then falls back to {@code SlotAssignment}. Read it before
     * {@link #onCraftPerformed()}, which advances the counter (and the step). An interleaved job's
     * cursor is per step, so it stays correct when a later batch comes back to a step.
     */
    public List<ItemKey> currentCraftLayout() {
        if (isFinished()) {
            return null;
        }
        if (interleaved) {
            int step = schedule.current();
            long done = schedule.done(step);
            List<SlotLayout<ItemKey>> runs = steps.get(step).layouts();
            if (layoutRunStartByStep[step] < 0 || done < layoutRunStartByStep[step]) { // latter: defensive
                layoutRunByStep[step] = 0;
                layoutRunStartByStep[step] = 0;
            }
            while (layoutRunByStep[step] < runs.size()
                    && done - layoutRunStartByStep[step] >= runs.get(layoutRunByStep[step]).crafts()) {
                layoutRunStartByStep[step] += runs.get(layoutRunByStep[step]).crafts();
                layoutRunByStep[step]++;
            }
            return layoutRunByStep[step] < runs.size() ? runs.get(layoutRunByStep[step]).items() : null;
        }
        List<SlotLayout<ItemKey>> runs = currentStep().layouts();
        // A cursor, not a walk from the first run: a step whose remainders flip-flop can carry one
        // run per craft, and rescanning them every craft would be quadratic in the step's crafts.
        if (layoutCursorStep != stepIndex || craftsDoneInStep < layoutRunStart) { // the latter: defensive
            layoutCursorStep = stepIndex;
            layoutRun = 0;
            layoutRunStart = 0;
        }
        while (layoutRun < runs.size() && craftsDoneInStep - layoutRunStart >= runs.get(layoutRun).crafts()) {
            layoutRunStart += runs.get(layoutRun).crafts();
            layoutRun++;
        }
        return layoutRun < runs.size() ? runs.get(layoutRun).items() : null;
    }

    public boolean isFinished() {
        return interleaved ? schedule.finished() : stepIndex >= steps.size();
    }

    /**
     * True while the current batch is the job's last — it runs the target step (the one that produces
     * the requested item), every other step is finished, and it holds exactly that step's remaining
     * crafts. Must be read before {@link #onCraftPerformed()} (which advances the batch).
     */
    public boolean isFinalStep() {
        return interleaved ? schedule.isFinalBatch() : stepIndex == steps.size() - 1;
    }

    /**
     * True when the craft about to be performed is the final craft of the current step
     * (the next {@link #onCraftPerformed()} will advance past it). Lets the grid-fill
     * hand-off divert only the very last craft of the final step — on an interleaved job, the whole
     * job's very last craft, which the schedule holds back until every other step is done.
     */
    public boolean isLastCraftOfStep() {
        if (interleaved) {
            return isFinalStep() && schedule.batch() == 1;
        }
        return craftsDoneInStep == currentStep().crafts() - 1;
    }

    /**
     * How many crafts of the current step are still to run ({@link #onCraftsPerformed} accepts at most
     * this many): the current batch when interleaved, the whole remainder of the step otherwise.
     */
    public long craftsLeftInStep() {
        return interleaved ? schedule.batch() : currentStep().crafts() - craftsDoneInStep;
    }

    /**
     * How many crafts still use the layout run {@link #currentCraftLayout()} returned: that run's
     * crafts less how many of it already ran, so a batch never spans two runs — the next run can
     * change every cell's item, and one batch applies one assignment.
     * {@link #craftsLeftInStep()} when the step carries no layout or the cursor is past the last run
     * (defensive, as in {@link #currentCraftLayout}): no run bounds a batch then.
     */
    public long craftsLeftInLayoutRun() {
        if (interleaved) {
            if (currentCraftLayout() == null) {
                return craftsLeftInStep();
            }
            int step = schedule.current();
            return layoutRunStartByStep[step]
                    + steps.get(step).layouts().get(layoutRunByStep[step]).crafts() - schedule.done(step);
        }
        return currentCraftLayout() == null
                ? craftsLeftInStep()
                : layoutRunStart + currentStep().layouts().get(layoutRun).crafts() - craftsDoneInStep;
    }

    /**
     * True when the current step runs in bursts; false for a paced step (one craft per
     * {@link #TICKS_PER_STEP}). Every step of an interleaved job bursts, whatever its size — the
     * batches are the point of such a job, not the per-step rhythm. Read before
     * {@link #onCraftPerformed()}, which may advance to the next step.
     */
    public boolean isBulkStep() {
        return interleaved || currentStep().crafts() > BULK_THRESHOLD;
    }

    /** Whether this job runs on a {@link DepthFirstSchedule} (some step exceeds an int's volume). */
    public boolean isInterleaved() {
        return interleaved;
    }

    /**
     * The current batch's output had nowhere to go: halve the schedule's batch cap so the next batch
     * is smaller (a smaller batch both fits a tighter network and lets the consuming steps run
     * first). Returns false when the batch is already a single craft at the per-craft floor — the
     * storage is genuinely full and the caller stops the job. The sequential path never shrinks.
     */
    public boolean shrinkForRoom() {
        return interleaved && schedule.noRoom();
    }

    /**
     * Whether this job bursts this server tick, so it shares the tick's burst budget (see
     * {@link BurstBudget}): a bulk step whose half-second wait is up. The budget is split when the
     * tick's first burst asks, before the players later in the loop have run their own {@link #tick()},
     * so a wait one tick short of done already counts. A job paused for a crafting table, or waiting out
     * the half second after a step boundary, has that wait reset and is not counted until it is nearly
     * up, so it does not shrink the slice of the jobs that do burst.
     */
    public boolean burstsThisTick() {
        return !isFinished() && isBulkStep() && progressTicks >= TICKS_PER_STEP - 1;
    }

    public long totalCrafts() {
        return totalCrafts;
    }

    /** Number of individual craft executions completed so far. */
    public long craftsDone() {
        return interleaved ? schedule.totalDone() : saturatingAdd(craftsBeforeStep, craftsDoneInStep);
    }

    /**
     * Advances the job timer by one tick.
     *
     * @return true if a craft is due now: one craft for a paced step, a burst bounded by
     *         {@link #BURST_BUDGET_NANOS} for a bulk step (the caller records what it applied with
     *         {@link #onCraftsPerformed(long)})
     */
    public boolean tick() {
        ticksSinceSync++;
        ticksSinceReachCheck++;
        if (isFinished()) {
            return false;
        }
        return ++progressTicks >= TICKS_PER_STEP;
    }

    /**
     * Holds a due craft for another half second — used when the current step needs a
     * crafting table and none is in reach (Factorio assembler waiting on its input).
     */
    public void holdForRetry() {
        progressTicks = 0;
    }

    /**
     * Called after the current step's craft was successfully applied to the inventory.
     *
     * @return true if that craft completed the step's run — the step itself on the sequential
     *         path, the current batch of an interleaved one. The half-second wait restarts after
     *         every craft of a paced step and at a step boundary (whose remaining crafts stay due,
     *         so the next burst runs on the following tick).
     */
    public boolean onCraftPerformed() {
        return onCraftsPerformed(1);
    }

    /**
     * Called after {@code n} crafts of the current step were successfully applied to the inventory in
     * one batched operation: the same semantics as {@code n} calls of {@link #onCraftPerformed()},
     * recorded in one go.
     *
     * @param n how many crafts were applied, 1..{@link #craftsLeftInStep()}
     * @return true if those crafts completed the step's run — the step itself on the sequential
     *         path, the current batch of an interleaved one. The half-second wait restarts after
     *         every craft of a paced step and at a step boundary — except every batch boundary of
     *         an interleaved job, whose steps all burst, where the next batch is due on the very
     *         next tick ({@code progressTicks = TICKS_PER_STEP - 1}) instead of restarting the
     *         half-second wait between two parts of what is really one long burst.
     */
    public boolean onCraftsPerformed(long n) {
        if (n < 1 || n > craftsLeftInStep()) {
            throw new IllegalArgumentException("crafts performed must be in [1, " + craftsLeftInStep() + "], got " + n);
        }
        if (interleaved) {
            schedule.performed(n);
            progressTicks = TICKS_PER_STEP - 1; // keep bursting into the next batch, as a burst's crafts do
            return true;
        }
        boolean paced = !isBulkStep();
        craftsDoneInStep += n;
        if (craftsDoneInStep >= currentStep().crafts()) {
            craftsBeforeStep = saturatingAdd(craftsBeforeStep, craftsDoneInStep);
            craftsDoneInStep = 0;
            stepIndex++;
            progressTicks = 0;
            return true;
        }
        if (paced) {
            progressTicks = 0;
        }
        return false;
    }

    /**
     * Whether a mid-step progress update should go out now (at most one per half second). Only a bulk
     * step's bursts need this throttle; a paced step syncs after every craft, and step completions and
     * terminal states always sync regardless.
     */
    public boolean progressSyncDue() {
        return ticksSinceSync >= TICKS_PER_STEP;
    }

    /** Records that a progress update was just sent. */
    public void markSynced() {
        ticksSinceSync = 0;
    }

    /**
     * Whether a full-grid step should run the full grid-access check (table scan, then open crafter)
     * before this craft. Always true for a step's first craft (it waited a full half second), then once
     * per half second while a bulk step keeps bursting every tick: the table scan is the expensive part,
     * and the pre-burst cadence checked it once per half second too. Between scans the burst re-validates
     * the table the scan found (cheap {@code CraftingTableReach.isStillInReach}) or the open crafter.
     */
    public boolean reachCheckDue() {
        return ticksSinceReachCheck >= TICKS_PER_STEP;
    }

    /**
     * Records a scan that passed and the crafting table it found (a failed one pauses via
     * {@link #holdForRetry()}); the table is what {@link #reachTable()} hands the cheap re-check.
     * {@code table} is null when access came from an open 3x3 crafter rather than a table block.
     */
    public void markReachChecked(BlockPos table) {
        ticksSinceReachCheck = 0;
        reachTable = table;
    }

    /** The table the last passing scan found; null if none has passed yet or access was an open crafter. */
    public BlockPos reachTable() {
        return reachTable;
    }

    /**
     * {@link #craftsDone()} for the int-valued progress message: exact up to {@link Integer#MAX_VALUE}
     * total crafts, scaled down proportionally with {@link #wireTotal()} beyond that so the ratio stays
     * right and nothing wraps negative. Since issue #46 this is only the fallback for clients without
     * the counts capability — a client that advertised it gets the real longs over the v2 channel.
     * (Main-channel message ids and layouts are frozen.)
     */
    public int wireDone() {
        return scaleToInt(craftsDone());
    }

    /** {@link #totalCrafts()} for the int-valued progress message; see {@link #wireDone()}. */
    public int wireTotal() {
        return scaleToInt(totalCrafts);
    }

    private int scaleToInt(long value) {
        if (totalCrafts <= Integer.MAX_VALUE) {
            return (int) value;
        }
        return (int) (value * ((double) Integer.MAX_VALUE / totalCrafts));
    }

    /** Progress through the whole job in [0, 1], for the HUD flyout. */
    public float progress() {
        return (float) ((craftsDone() + (double) Math.min(progressTicks, TICKS_PER_STEP) / TICKS_PER_STEP)
                / totalCrafts);
    }
}
