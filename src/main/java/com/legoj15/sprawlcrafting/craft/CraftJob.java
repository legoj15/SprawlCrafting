package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
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
 * <p>Cadence: each step waits {@link #TICKS_PER_STEP} ticks, then runs in bursts of up to
 * {@link #MAX_CRAFTS_PER_TICK} crafts (and at most {@link #BURST_BUDGET_NANOS} of server time), one
 * burst per tick, until the step is done. A small step (the usual 1-16 crafts) therefore finishes in
 * its first burst, half a second after the previous step; a batched step from a compressed-block
 * chain (e.g. 6,561 crafts) takes roughly 5-16 seconds instead of the ~55 minutes one craft per half
 * second would take.
 */
public final class CraftJob {

    /** Half a second between steps. */
    public static final int TICKS_PER_STEP = 10;

    /**
     * Most crafts one tick may perform for this player: one stack of a single-output recipe (the usual
     * compression case), which clears a 6,561-craft step in ~103 ticks when the time budget allows.
     */
    public static final int MAX_CRAFTS_PER_TICK = 64;

    /**
     * Wall-time budget for one player's burst; the burst stops at whichever of this and
     * {@link #MAX_CRAFTS_PER_TICK} comes first (always at least one craft). Measured on 1.21.1
     * NeoForge (game-test server, mock player, 9-ingredient iron_block recipe with the ingots behind 27
     * filler stacks — the worst slot-scan layout): a 64-craft burst took 15.4 ms cold, then 2.5-6.1 ms
     * warm (~40-95 us per craft). Unbounded, that is up to a third of a 50 ms tick for one player, so the
     * budget caps it at 2 ms (4% of a tick): a cheap recipe still runs the full 64, a costly one ~20-50.
     */
    public static final long BURST_BUDGET_NANOS = 2_000_000L;

    private final ResourceLocation targetRecipe;
    private final ItemStack targetResult;
    private final List<CraftStep> steps;
    private final long totalCrafts;

    private int stepIndex;
    private int craftsDoneInStep;
    /** Crafts of all steps before {@link #stepIndex}, so {@link #craftsDone()} is O(1). */
    private long craftsBeforeStep;
    private int progressTicks;
    private int ticksSinceSync;
    private int ticksSinceReachCheck = TICKS_PER_STEP;

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

    public long totalCrafts() {
        return totalCrafts;
    }

    /** Number of individual craft executions completed so far. */
    public long craftsDone() {
        return craftsBeforeStep + craftsDoneInStep;
    }

    /**
     * Advances the job timer by one tick.
     *
     * @return true if a burst of crafts is due now (the caller performs up to
     *         {@link #MAX_CRAFTS_PER_TICK} crafts, calling {@link #onCraftPerformed()} after each)
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
     * @return true if that craft completed the step. The half-second wait restarts only then; a step
     *         with crafts left stays due, so its next burst runs on the following tick.
     */
    public boolean onCraftPerformed() {
        if (++craftsDoneInStep >= currentStep().crafts()) {
            craftsBeforeStep = saturatingAdd(craftsBeforeStep, craftsDoneInStep);
            craftsDoneInStep = 0;
            stepIndex++;
            progressTicks = 0;
            return true;
        }
        return false;
    }

    /**
     * Whether a mid-step progress update should go to the client now. Bursts run every tick, but the
     * toast only needs (and its per-update step sound should only get) one update per half second;
     * step completions and terminal states always sync regardless.
     */
    public boolean progressSyncDue() {
        return ticksSinceSync >= TICKS_PER_STEP;
    }

    /** Records that a progress update was just sent (restarts the {@link #progressSyncDue()} clock). */
    public void markSynced() {
        ticksSinceSync = 0;
    }

    /**
     * Whether a full-grid step should re-check crafting-table reach before this burst. Always true for
     * a step's first burst (it waited a full half second), then once per half second while a long step
     * keeps bursting every tick — the reach scan is the expensive part, and the pre-burst cadence
     * checked it once per half second too.
     */
    public boolean reachCheckDue() {
        return ticksSinceReachCheck >= TICKS_PER_STEP;
    }

    /** Records a reach check that passed (a failed one pauses via {@link #holdForRetry()}). */
    public void markReachChecked() {
        ticksSinceReachCheck = 0;
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
