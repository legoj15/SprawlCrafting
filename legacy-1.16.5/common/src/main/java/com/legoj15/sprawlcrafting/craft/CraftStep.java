package com.legoj15.sprawlcrafting.craft;

import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.legoj15.solver.SlotLayout;

import net.minecraft.util.ResourceLocation;
import net.minecraft.item.Item;

/**
 * A single unit of work in a craft chain: execute {@code recipeId} {@code crafts} times. Each
 * execution consumes its ingredients from the player's live inventory and places the result back
 * into it as real items. {@code needsFullGrid} steps only execute with a crafting table in reach.
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
    private final List<SlotLayout<Item>> layouts;

    public CraftStep(ResourceLocation recipeId, int crafts, boolean needsFullGrid) {
        this(recipeId, crafts, needsFullGrid, Collections.<SlotLayout<Item>>emptyList());
    }

    public CraftStep(ResourceLocation recipeId, int crafts, boolean needsFullGrid,
                     List<SlotLayout<Item>> layouts) {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        this.recipeId = recipeId;
        this.crafts = crafts;
        this.needsFullGrid = needsFullGrid;
        this.layouts = layouts == null ? ImmutableList.<SlotLayout<Item>>of() : ImmutableList.copyOf(layouts);
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
    public List<SlotLayout<Item>> layouts() {
        return layouts;
    }
}
