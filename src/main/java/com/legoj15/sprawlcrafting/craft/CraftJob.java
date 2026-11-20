package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import com.legoj15.solver.ProducedLedger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

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
 * seconds instead of the ~55 minutes one craft per half second would take.
 */
public final class CraftJob {

    /** Half a second between crafts of a paced step, and between steps. */
    public static final int TICKS_PER_STEP = 10;

    /**
     * Largest step that is still paced one craft per {@link #TICKS_PER_STEP}. Ordinary recipes need a
     * handful of crafts per step (even a full stack of sticks is 16), and for those the half-second craft-by-craft
     * rhythm, with its visible progress and sound, is the intended experience. A step with more crafts
     * than this is a batched step from a compression chain, where that rhythm would take minutes to
     * hours, so it runs in bursts instead.
     */
    public static final int BULK_THRESHOLD = 16;

    /**
     * Wall-time budget for one player's burst; the burst stops at whichever of this and
     * the step boundary comes first (always at least one craft; there is no count cap, so this is what
     * bounds a burst). Measured on 1.21.1
     * NeoForge (game-test server, mock player, 9-ingredient iron_block recipe with the ingots behind 27
     * filler stacks — the worst slot-scan layout): a 64-craft burst took 15.4 ms cold, then 2.5-6.1 ms
     * warm (~40-95 us per craft). Unbounded, that is up to a third of a 50 ms tick for one player, so the
     * budget caps it at 2 ms (4% of a tick): at ~40-95 us a craft that is ~20-50 crafts a tick, and a cheaper recipe runs more.
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
    private final ProducedLedger<Item, CraftExecutor.LedgerVariant> produced = new ProducedLedger<>();

    private int stepIndex;
    private int craftsDoneInStep;
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
     * ({@code CraftRequests}), and later the first time a craft actually draws from the station or
     * stores overflow in it ({@code CraftQueueManager}), e.g. because the player moved items out of the inventory mid-job.
     * Such a job pauses instead of cancelling when an ingredient is missing while the station is
     * closed or unusable: the item may be in the storage the player can no longer reach. A job that
     * never needed the station keeps the plain cancel. (1.12.2's {@code CraftJob.externalDependent}.)
     */
    private boolean stationDependent;

    public CraftJob(ResourceLocation targetRecipe, ItemStack targetResult, List<CraftStep> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a craft job needs at least one step");
        }
        this.targetRecipe = targetRecipe;
        this.targetResult = targetResult.copy();
        this.steps = List.copyOf(steps);
        long total = 0;
        for (CraftStep step : this.steps) {
            total = saturatingAdd(total, step.crafts());
        }
        this.totalCrafts = total;
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
     * hand-off divert only the very last craft of the final step, auto-crafting any
     * earlier crafts of a multi-craft step normally.
     */
    public boolean isLastCraftOfStep() {
        return craftsDoneInStep == currentStep().crafts() - 1;
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
     *         {@link #BURST_BUDGET_NANOS} for a bulk step (the caller calls {@link #onCraftPerformed()}
     *         after each)
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
     *         so the next burst runs on the following tick).
     */
    public boolean onCraftPerformed() {
        boolean paced = !isBulkStep();
        if (++craftsDoneInStep >= currentStep().crafts()) {
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
     * Whether a mid-step progress update should go to the client now. A bulk step's bursts run every
     * tick, but the toast only needs (and its per-update step sound should only get) one update per half
     * second; a paced step syncs after every craft instead, and step completions and terminal states
     * always sync regardless.
     */
    public boolean progressSyncDue() {
        return ticksSinceSync >= TICKS_PER_STEP;
    }

    /** Records that a progress update was just sent (restarts the {@link #progressSyncDue()} clock). */
    public void markSynced() {
        ticksSinceSync = 0;
    }

    /**
     * Whether a full-grid step should run the full crafting-table scan before this craft. Always true
     * for a step's first craft (it waited a full half second), then once per half second while a bulk
     * step keeps bursting every tick: the scan is the expensive part, and the pre-burst cadence checked
     * it once per half second too. Between scans the burst re-validates the table the scan found with
     * the cheap {@code CraftingTableReach.isStillInReach} instead.
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
     * {@link #craftsDone()} for the int-valued progress wire field: exact up to {@link Integer#MAX_VALUE}
     * total crafts, scaled down proportionally with {@link #wireTotal()} beyond that so the toast's
     * ratio stays right and nothing wraps negative.
     */
    public int wireDone() {
        return scaleToInt(craftsDone());
    }

    /** {@link #totalCrafts()} for the int-valued progress wire field; see {@link #wireDone()}. */
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
