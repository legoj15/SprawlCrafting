package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import com.legoj15.solver.SlotLayout;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * A single unit of work in a craft chain: execute {@code recipeId} {@code crafts} times.
 * Each execution consumes its ingredients from the player's live inventory and places the
 * result back into it as real items, so later steps (and the player) can see and use them.
 *
 * <p>A step starts {@link CraftJob#TICKS_PER_STEP} ticks after the previous one and then runs as many
 * crafts per tick as {@link CraftJob#BURST_BUDGET_NANOS} allows until all {@code crafts} are done.
 *
 * <p>{@code layouts} carries the plan's own per-slot item choice for the step's crafts (see
 * {@link SlotLayout}): the executor follows it and falls back to {@code SlotAssignment} only when
 * the live pool no longer matches. Empty means no layout — the executor decides each craft with
 * {@code SlotAssignment} itself, as every step did before plans carried one.
 *
 * @param needsFullGrid true if the recipe does not fit a 2×2 grid; such steps only
 *                      execute while a crafting table is within the player's reach
 */
public record CraftStep(ResourceLocation recipeId, int crafts, boolean needsFullGrid,
                        List<SlotLayout<Item>> layouts) {

    public CraftStep {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        layouts = layouts == null ? List.of() : List.copyOf(layouts);
    }

    /** A step with no layout: the executor picks each craft's items with {@code SlotAssignment}. */
    public CraftStep(ResourceLocation recipeId, int crafts, boolean needsFullGrid) {
        this(recipeId, crafts, needsFullGrid, List.of());
    }
}
