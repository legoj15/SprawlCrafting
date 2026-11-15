package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.legoj15.solver.ProducedLedger;

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
 * once, then runs in bursts of up to {@link #MAX_CRAFTS_PER_TICK} crafts (and at most
 * {@link #BURST_BUDGET_NANOS} of server time, and all players' bursts together at most
 * {@link #SERVER_TICK_BURST_BUDGET_NANOS}), one burst per tick, until the step is done: roughly 5-16
 * seconds instead of the ~55 minutes one craft per half second would take.
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
     * Most crafts one tick may perform for this player — same cap and reasoning as the modern tree: one
     * craft is a recipe lookup, an ingredient match over the main slots and an insert (microseconds),
     * so 64 (a stack of a single-output recipe) stays far below the 50 ms tick.
     */
    public static final int MAX_CRAFTS_PER_TICK = 64;

    /**
     * Wall-time budget for one player's burst; the burst stops at whichever of this and
     * {@link #MAX_CRAFTS_PER_TICK} comes first (always at least one craft). Same value as the modern
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
    private final ProducedLedger<ItemKey> produced = new ProducedLedger<ItemKey>();

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
     * True once this job relies on a connected station inventory (a Crafting Station's chest): it was
     * planned with one in the pool, or a craft has since consumed from it. When set, a step that can't
     * find an ingredient while no station chest is open PAUSES (the materials may be in the unseen
     * chest) instead of cancelling — see {@code CraftQueueManager}. Kept apart from
     * {@link #networkDependent} so each source is judged by its own visibility.
     */
    private boolean chestDependent;
    /**
     * Same for an open network crafting terminal's network (AE2): planned with it in the pool, or a
     * craft has since consumed from it. A job started with the terminal closed can still become
     * dependent mid-job, the first time a craft draws network stock.
     */
    private boolean networkDependent;

    public CraftJob(ResourceLocation targetRecipe, ItemStack targetResult, List<CraftStep> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a craft job needs at least one step");
        }
        this.targetRecipe = targetRecipe;
        this.targetResult = targetResult.copy();
        this.steps = Collections.unmodifiableList(new ArrayList<CraftStep>(steps));
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

    /** Whether the job relies on any station-side source, i.e. its pauses point the player back to a station. */
    public boolean stationDependent() {
        return chestDependent || networkDependent;
    }

    /** The final output, for chat/HUD display. Do not mutate. */
    public ItemStack targetResult() {
        return targetResult;
    }

    /** What this job crafted and has not used back up; lets the executor take its own altered intermediates. */
    public ProducedLedger<ItemKey> produced() {
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
     * @return true if a craft is due now: one craft for a paced step, a burst of up to
     *         {@link #MAX_CRAFTS_PER_TICK} for a bulk step (the caller calls {@link #onCraftPerformed()}
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
     * right and nothing wraps negative. (Main-channel message ids and layouts are frozen.)
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
