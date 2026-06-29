package com.legoj15.sprawlcrafting.forge.craft;

import net.minecraft.util.ResourceLocation;

/**
 * A single unit of work in a craft chain: execute {@code recipeId} {@code crafts} times.
 * Each execution consumes its ingredients from the player's live inventory and places the
 * result back into it as real items, so later steps (and the player) can see and use them.
 *
 * <p>One step execution completes every {@link CraftJob#TICKS_PER_STEP} ticks.
 *
 * <p>Java-8 plain-class port of the modern record (1.12.2 predates records by eight Java
 * releases). {@code needsFullGrid} is true if the recipe does not fit a 2x2 grid; such
 * steps only execute while a crafting table is within the player's reach.
 */
public final class CraftStep {

    private final ResourceLocation recipeId;
    private final int crafts;
    private final boolean needsFullGrid;

    public CraftStep(ResourceLocation recipeId, int crafts, boolean needsFullGrid) {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        this.recipeId = recipeId;
        this.crafts = crafts;
        this.needsFullGrid = needsFullGrid;
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
}
