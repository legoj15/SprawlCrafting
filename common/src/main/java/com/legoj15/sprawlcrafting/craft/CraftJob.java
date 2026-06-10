package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * An in-progress deferred craft: an ordered list of {@link CraftStep}s ending with the
 * step that produces the requested output. Steps are ordered so that every step's
 * ingredients are either raw resources or outputs of earlier steps (a topological order
 * of the recipe dependency tree).
 *
 * <p>Ingredients are consumed per step from the live inventory, not reserved upfront.
 * If a step's ingredients are missing when it comes due (player dropped/used them),
 * the job fails gracefully and the player is notified; items already crafted stay in
 * the inventory.
 */
public final class CraftJob {

    /** Half a second per component craft. */
    public static final int TICKS_PER_STEP = 10;

    private final ResourceLocation targetRecipe;
    private final List<CraftStep> steps;

    private int stepIndex;
    private int craftsDoneInStep;
    private int progressTicks;

    public CraftJob(ResourceLocation targetRecipe, List<CraftStep> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a craft job needs at least one step");
        }
        this.targetRecipe = targetRecipe;
        this.steps = List.copyOf(steps);
    }

    public ResourceLocation targetRecipe() {
        return targetRecipe;
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
        int totalCrafts = steps.stream().mapToInt(CraftStep::crafts).sum();
        int doneCrafts = craftsDoneInStep;
        for (int i = 0; i < stepIndex; i++) {
            doneCrafts += steps.get(i).crafts();
        }
        return (doneCrafts + (float) progressTicks / TICKS_PER_STEP) / totalCrafts;
    }
}
