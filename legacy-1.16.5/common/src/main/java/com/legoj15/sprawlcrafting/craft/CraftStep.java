package com.legoj15.sprawlcrafting.craft;

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
 *
 * <p>{@code result}/{@code resultCount} name the recipe's output, what one craft of the step
 * produces; the depth-first schedule of an over-int job sizes and interleaves its batches from
 * them (a step is over-int when {@code crafts * max(resultCount, slots)} exceeds an int).
 */
public final class CraftStep {

    private final ResourceLocation recipeId;
    private final long crafts;
    private final boolean needsFullGrid;
    private final List<SlotLayout<Item>> layouts;
    private final Item result;
    private final int resultCount;

    public CraftStep(ResourceLocation recipeId, long crafts, boolean needsFullGrid,
                     List<SlotLayout<Item>> layouts, Item result, int resultCount) {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        this.recipeId = recipeId;
        this.crafts = crafts;
        this.needsFullGrid = needsFullGrid;
        this.layouts = layouts == null ? ImmutableList.<SlotLayout<Item>>of() : ImmutableList.copyOf(layouts);
        this.result = result;
        this.resultCount = resultCount;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public long crafts() {
        return crafts;
    }

    public boolean needsFullGrid() {
        return needsFullGrid;
    }

    /** The plan's per-slot item runs for this step's crafts; empty when the step carries no layout. */
    public List<SlotLayout<Item>> layouts() {
        return layouts;
    }

    /** The recipe's output item, or null when the recipe is no longer resolvable. */
    public Item result() {
        return result;
    }

    /** How many of {@link #result()} one craft produces (0 when the recipe is no longer resolvable). */
    public int resultCount() {
        return resultCount;
    }

    /** The step's slot count: every run of its layout lists the same number of slots. */
    public int slotCount() {
        return layouts.isEmpty() ? 0 : layouts.get(0).items().size();
    }
}
