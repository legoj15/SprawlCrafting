package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import com.legoj15.solver.ProducedLedger;
import com.legoj15.solver.SlotLayout;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * An in-progress deferred craft: an ordered list of {@link CraftStep}s ending with the
 * step that produces the requested output. Steps are ordered so that every step's
 * ingredients are either raw resources or outputs of earlier steps (a topological order
 * of the recipe dependency tree, produced by {@code CraftPlanner}).
 *
 * <p>Ingredients are consumed per craft from the live inventory, not reserved upfront.
 * If a step's ingredients are missing when it comes due (player dropped/used them),
 * the job fails gracefully and the player is notified; items already crafted stay in
 * the inventory.
 *
 * <p>Cadence, by step size. A <em>paced</em> step (at most {@link #BULK_THRESHOLD} crafts: every
 * ordinary recipe) keeps the Factorio-style rhythm: one craft per {@link #TICKS_PER_STEP} ticks, each
 * with its own progress update and step sound. A <em>bulk</em> step (more than {@link #BULK_THRESHOLD}
 * crafts, i.e. a batched compressed-block chain such as 6,561 crafts) waits {@link #TICKS_PER_STEP} ticks
 * once, then runs in bursts of as many crafts as fit in
 * {@link #BURST_BUDGET_NANOS} of server time (and all players' bursts together at most
 * {@link #SERVER_TICK_BURST_BUDGET_NANOS}), one burst per tick, until the step is done: roughly 5-16
 * seconds instead of the ~55 minutes one craft per half second would take. A burst applies its crafts
 * in batches ({@code CraftExecutor#craftBatch}) wherever one assignment provably covers many crafts;
 * at the boundary between two bulk steps the next step is due on the very next tick rather than
 * waiting another half second, so a compression chain runs as one long burst split only by the
 * time budget.
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
    private final ProducedLedger<Item, CraftExecutor.LedgerVariant> produced =
            new ProducedLedger<Item, CraftExecutor.LedgerVariant>();

    private int stepIndex;
    private int craftsDoneInStep;
    /** {@link #currentCraftLayout}'s cursor: the step it is for, its run, and that run's first craft. */
    private int layoutCursorStep = -1;
    private int layoutRun;
    private int layoutRunStart;
    /** Crafts of all steps before {@link #stepIndex}, so {@link #craftsDone()} is O(1). */
    private long craftsBeforeStep;
    private int progressTicks;
    private int ticksSinceSync;
    private int ticksSinceReachCheck = TICKS_PER_STEP;
    /** The crafting table the last passing reach scan found; the cheap per-tick re-check watches it. */
    private BlockPos reachTable;
    /**
     * Whether the job relies on a crafting station's storage (AE2 Crafting Terminal, see
     * {@link StationStorages}): set at start when the plan could not be made from the inventory alone
     * ({@code CraftRequests}), and later the first time a craft actually draws from the station or stores
     * overflow in it ({@code CraftQueueManager}), e.g. because the player moved items out mid-job. Such a
     * job pauses instead of cancelling when an ingredient is missing while the station is closed or
     * unusable: the item may be in the storage the player can no longer reach. A job that never needed
     * the station keeps the plain cancel.
     */
    private boolean stationDependent;
    /**
     * Whether this job's plan counted the player's protected stacks (enchanted and/or renamed, never
     * damaged; {@code CraftExecutor#isProtected}) as material — set by the caller that planned it
     * that way (issue #40), after the plain pool fell short and the player confirmed. Such a job may
     * pick protected stacks from the 36 main slots, always after every plain stack and an open
     * station's stock; every other job keeps the plain rule exactly. Damaged stacks are never
     * protected material, on any job.
     */
    private boolean usesProtected;

    public CraftJob(ResourceLocation targetRecipe, ItemStack targetResult, List<CraftStep> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a craft job needs at least one step");
        }
        this.targetRecipe = targetRecipe;
        this.targetResult = targetResult.copy();
        this.steps = com.google.common.collect.ImmutableList.copyOf(steps);
        long sum = 0;
        for (CraftStep step : this.steps) {
            sum = saturatingAdd(sum, step.crafts());
        }
        this.totalCrafts = sum;
    }

    /** {@code a + b} for non-negative counts, pinned at {@link Long#MAX_VALUE} instead of wrapping. */
    public static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public ResourceLocation targetRecipe() {
        return targetRecipe;
    }

    /** The final output, for chat/HUD display. Do not mutate. */
    public ItemStack targetResult() {
        return targetResult;
    }

    /** See {@link #stationDependent}. */
    public boolean stationDependent() {
        return stationDependent;
    }

    public void setStationDependent(boolean stationDependent) {
        this.stationDependent = stationDependent;
    }

    /** See {@link #usesProtected}. */
    public boolean usesProtected() {
        return usesProtected;
    }

    public void setUsesProtected(boolean usesProtected) {
        this.usesProtected = usesProtected;
    }

    /** What this job crafted and has not used back up; lets the executor take its own altered intermediates. */
    public ProducedLedger<Item, CraftExecutor.LedgerVariant> produced() {
        return produced;
    }

    public List<CraftStep> steps() {
        return steps;
    }

    public CraftStep currentStep() {
        return steps.get(stepIndex);
    }

    /**
     * The plan's per-slot items for the craft about to run — the layout run covering
     * {@code craftsDoneInStep} within the current step — or null when the step carries no layout
     * (a step built without one, or the counter past the last run: defensive, the step advances with
     * the last craft) or the job is finished: the executor then falls back to {@code SlotAssignment}.
     * Read it before {@link #onCraftPerformed()}, which advances the counter (and the step).
     */
    public List<Item> currentCraftLayout() {
        if (isFinished()) {
            return null;
        }
        List<SlotLayout<Item>> runs = currentStep().layouts();
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
        return stepIndex >= steps.size();
    }

    /**
     * True while the current step is the last one — the step that produces the requested
     * item. Must be read before {@link #onCraftPerformed()} (which advances {@code stepIndex}).
     */
    public boolean isFinalStep() {
        return stepIndex == steps.size() - 1;
    }

    /**
     * True when the craft about to be performed is the final craft of the current step
     * (the next {@link #onCraftPerformed()} will advance past it). Lets the grid-fill
     * hand-off divert only the very last craft of the final step.
     */
    public boolean isLastCraftOfStep() {
        return craftsDoneInStep == currentStep().crafts() - 1;
    }

    /** How many crafts of the current step are still to run ({@link #onCraftsPerformed} accepts at most this many). */
    public int craftsLeftInStep() {
        return currentStep().crafts() - craftsDoneInStep;
    }

    /**
     * How many crafts still use the layout run {@link #currentCraftLayout()} returned: that run's
     * crafts less how many of it already ran, so a batch ({@code CraftExecutor#craftBatch}) never
     * spans two runs — the next run can change every cell's item, and one batch applies one
     * assignment. {@link #craftsLeftInStep()} when the step carries no layout or the cursor is past
     * the last run (defensive, as in {@link #currentCraftLayout}): no run bounds a batch then.
     */
    public int craftsLeftInLayoutRun() {
        return currentCraftLayout() == null
                ? craftsLeftInStep()
                : layoutRunStart + currentStep().layouts().get(layoutRun).crafts() - craftsDoneInStep;
    }

    /**
     * True when the current step has more than {@link #BULK_THRESHOLD} crafts and so runs in bursts;
     * false for a paced step (one craft per {@link #TICKS_PER_STEP}). Read before
     * {@link #onCraftPerformed()}, which may advance to the next step.
     */
    public boolean isBulkStep() {
        return currentStep().crafts() > BULK_THRESHOLD;
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
        return saturatingAdd(craftsBeforeStep, craftsDoneInStep);
    }

    /**
     * Advances the job timer by one tick.
     *
     * @return true if a craft is due now: one craft for a paced step, a burst bounded by
     *         {@link #BURST_BUDGET_NANOS} for a bulk step (the caller records what it applied with
     *         {@link #onCraftsPerformed(int)})
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
     * @return true if that craft completed the step. The half-second wait restarts after every craft of
     *         a paced step and only at the step boundary of a bulk step (whose remaining crafts stay due,
     *         so the next burst runs on the following tick — or immediately so at a bulk-to-bulk
     *         boundary, see {@link #onCraftsPerformed(int)}).
     */
    public boolean onCraftPerformed() {
        return onCraftsPerformed(1);
    }

    /**
     * Called after {@code n} crafts of the current step were successfully applied to the inventory in
     * one batched operation ({@code CraftExecutor#craftBatch}): the same semantics as {@code n} calls
     * of {@link #onCraftPerformed()}, recorded in one go.
     *
     * @param n how many crafts were applied, 1..{@link #craftsLeftInStep()}
     * @return true if those crafts completed the step. The half-second wait restarts after every craft
     *         of a paced step and at a step boundary — except the boundary of a bulk step whose next
     *         step is bulk too, where the next step is due on the very next tick
     *         ({@code progressTicks = TICKS_PER_STEP - 1}) instead of restarting the half-second wait
     *         between two parts of what is really one long burst.
     */
    public boolean onCraftsPerformed(int n) {
        if (n < 1 || n > craftsLeftInStep()) {
            throw new IllegalArgumentException("crafts performed must be in [1, " + craftsLeftInStep() + "], got " + n);
        }
        boolean paced = !isBulkStep();
        craftsDoneInStep += n;
        if (craftsDoneInStep >= currentStep().crafts()) {
            boolean nextBulkContinues = isBulkStep() && stepIndex + 1 < steps.size()
                    && steps.get(stepIndex + 1).crafts() > BULK_THRESHOLD;
            craftsBeforeStep = saturatingAdd(craftsBeforeStep, craftsDoneInStep);
            craftsDoneInStep = 0;
            stepIndex++;
            progressTicks = nextBulkContinues ? TICKS_PER_STEP - 1 : 0;
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
     * Whether a full-grid step should run the full crafting-table scan before this craft. True for a step's
     * first craft when that step waited its half second (a bulk step that follows another bulk step does
     * not wait, and keeps the previous step's scan cadence), then once per half second while a bulk step
     * keeps bursting every tick: the scan is the expensive part, and the pre-burst cadence checked it
     * once per half second too. Between scans the burst re-validates the table the scan found with the
     * cheap {@code CraftingTableReach.isStillInReach} instead.
     */
    public boolean reachCheckDue() {
        return ticksSinceReachCheck >= TICKS_PER_STEP;
    }

    /**
     * Records a scan that passed and the crafting table it found (a failed one pauses via
     * {@link #holdForRetry()}); the table is what {@link #reachTable()} hands the cheap re-check.
     */
    public void markReachChecked(BlockPos table) {
        ticksSinceReachCheck = 0;
        reachTable = table;
    }

    /** The table the last passing scan found, or null if none has passed yet. */
    public BlockPos reachTable() {
        return reachTable;
    }

    /**
     * {@link #craftsDone()} for the int-valued progress packet: exact up to {@link Integer#MAX_VALUE}
     * total crafts, scaled down proportionally with {@link #wireTotal()} beyond that so the ratio stays
     * right and nothing wraps negative.
     */
    public int wireDone() {
        return scaleToInt(craftsDone());
    }

    /** {@link #totalCrafts()} for the int-valued progress packet; see {@link #wireDone()}. */
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
