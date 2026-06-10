package com.legoj15.sprawlcrafting.craft;

import net.minecraft.resources.ResourceLocation;

/**
 * A single unit of work in a craft chain: execute {@code recipeId} {@code crafts} times.
 * Each execution consumes its ingredients from the player's live inventory and places the
 * result back into it as real items, so later steps (and the player) can see and use them.
 *
 * <p>One step execution completes every {@link CraftJob#TICKS_PER_STEP} ticks.
 */
public record CraftStep(ResourceLocation recipeId, int crafts) {

    public CraftStep {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
    }
}
