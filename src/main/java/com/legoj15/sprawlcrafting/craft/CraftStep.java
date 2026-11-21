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
 * <p>{@code result}/{@code resultCount} name the recipe's output, what one craft of the step
 * produces; the depth-first schedule of an over-int job sizes and interleaves its batches from
 * them (a step is over-int when {@code crafts * max(resultCount, slots)} exceeds an int).
 *
 * @param needsFullGrid true if the recipe does not fit a 2×2 grid; such steps only
 *                      execute while a crafting table is within the player's reach
 * @param layouts       the plan's {@link SlotLayout} runs for this step's crafts, in order (empty: none)
 * @param result        the recipe's output item (null when the recipe is no longer resolvable)
 * @param resultCount   how many of {@code result} one craft produces (0 when the recipe is no longer
 *                      resolvable)
 */
public record CraftStep(ResourceLocation recipeId, long crafts, boolean needsFullGrid,
                        List<SlotLayout<Item>> layouts, Item result, int resultCount) {

    public CraftStep {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        layouts = layouts == null ? List.of() : List.copyOf(layouts);
    }

    /** The step's slot count: every run of its layout lists the same number of slots. */
    public int slotCount() {
        return layouts.isEmpty() ? 0 : layouts.get(0).items().size();
    }
}
