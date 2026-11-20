package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.legoj15.solver.SlotLayout;

import net.minecraft.util.ResourceLocation;

/**
 * A single unit of work in a craft chain: execute {@code recipeId} {@code crafts} times.
 * Each execution consumes its ingredients from the player's live inventory and places the
 * result back into it as real items, so later steps (and the player) can see and use them.
 *
 * <p>A step starts {@link CraftJob#TICKS_PER_STEP} ticks after the previous one; a small step then runs
 * one craft per {@link CraftJob#TICKS_PER_STEP} ticks, a bulk one as many crafts per tick as
 * {@link CraftJob#BURST_BUDGET_NANOS} allows.
 *
 * <p>Java-8 plain-class port of the modern record (1.12.2 predates records by eight Java
 * releases). {@code needsFullGrid} is true if the recipe does not fit a 2x2 grid; such
 * steps only execute while a crafting table is within the player's reach.
 *
 * <p>{@code layouts} carries the plan's own per-slot item choice for the step's crafts (see
 * {@link SlotLayout}): the executor follows it and falls back to {@code SlotAssignment} only when
 * the live pool no longer matches. Empty means no layout — the executor decides each craft with
 * {@code SlotAssignment} itself, as every step did before plans carried one.
 */
public final class CraftStep {

    private final ResourceLocation recipeId;
    private final int crafts;
    private final boolean needsFullGrid;
    private final List<SlotLayout<ItemKey>> layouts;

    public CraftStep(ResourceLocation recipeId, int crafts, boolean needsFullGrid,
                     List<SlotLayout<ItemKey>> layouts) {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        this.recipeId = recipeId;
        this.crafts = crafts;
        this.needsFullGrid = needsFullGrid;
        this.layouts = layouts == null
                ? Collections.<SlotLayout<ItemKey>>emptyList()
                : Collections.unmodifiableList(new ArrayList<SlotLayout<ItemKey>>(layouts));
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public int crafts() {
        return crafts;
    }

    public boolean needsFullGrid() {
        return needsFullGrid;
    }

    /** The plan's per-slot item runs for this step's crafts; empty when the step carries no layout. */
    public List<SlotLayout<ItemKey>> layouts() {
        return layouts;
    }
}
