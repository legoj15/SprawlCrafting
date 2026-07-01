package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * An in-progress deferred craft: an ordered list of {@link CraftStep}s ending with the
 * step that produces the requested output. Steps are ordered so that every step's
 * ingredients are either raw resources or outputs of earlier steps (a topological order
 * of the recipe dependency tree, produced by {@code CraftPlanner}).
 *
 * <p>Ingredients are consumed per step from the live inventory, not reserved upfront. If a
 * step's ingredients are missing when it comes due (player dropped/used them), the job
 * fails gracefully and the player is notified; items already crafted stay in the inventory.
 *
 * <p>Java-8 plain-class port of the modern {@code CraftJob}: {@code List.copyOf} (Java 10)
 * is replaced with {@code Collections.unmodifiableList(new ArrayList<>(...))}; the logic is
 * otherwise identical.
 */
public final class CraftJob {

    /** Half a second per component craft. */
    public static final int TICKS_PER_STEP = 10;

    private final ResourceLocation targetRecipe;
    private final ItemStack targetResult;
    private final List<CraftStep> steps;
    private final int totalCrafts;

    private int stepIndex;
    private int craftsDoneInStep;
    private int progressTicks;
    /**
     * True if this job was started with a connected station inventory (a Crafting Station's chest)
     * available, so its plan may rely on chest items. When set, a step that can't find an ingredient
     * while the station is closed PAUSES (the materials may be in the unseen chest) instead of
     * cancelling — see {@code CraftQueueManager}.
     */
    private boolean externalDependent;

    public CraftJob(ResourceLocation targetRecipe, ItemStack targetResult, List<CraftStep> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a craft job needs at least one step");
        }
        this.targetRecipe = targetRecipe;
        this.targetResult = targetResult.copy();
        this.steps = Collections.unmodifiableList(new ArrayList<CraftStep>(steps));
        int total = 0;
        for (CraftStep step : this.steps) {
            total += step.crafts();
        }
        this.totalCrafts = total;
    }

    public ResourceLocation targetRecipe() {
        return targetRecipe;
    }

    public boolean externalDependent() {
        return externalDependent;
    }

    public void setExternalDependent(boolean externalDependent) {
        this.externalDependent = externalDependent;
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

    public int totalCrafts() {
        return totalCrafts;
    }

    /** Number of individual craft executions completed so far. */
    public int craftsDone() {
        int done = craftsDoneInStep;
        for (int i = 0; i < stepIndex; i++) {
            done += steps.get(i).crafts();
        }
        return done;
    }

    /**
     * Advances the half-second timer by one tick.
     *
     * @return true if a craft execution is now due (the caller performs the actual
     *         inventory mutation and then calls {@link #onCraftPerformed()})
     */
    public boolean tick() {
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

    /** Called after the current step's craft was successfully applied to the inventory. */
    public void onCraftPerformed() {
        progressTicks = 0;
        if (++craftsDoneInStep >= currentStep().crafts()) {
            craftsDoneInStep = 0;
            stepIndex++;
        }
    }

    /** Progress through the whole job in [0, 1], for the HUD flyout. */
    public float progress() {
        return (craftsDone() + (float) progressTicks / TICKS_PER_STEP) / totalCrafts;
    }
}
