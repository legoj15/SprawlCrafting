package com.legoj15.sprawlcrafting.craft;

import net.minecraft.util.ResourceLocation;

/**
 * A single unit of work in a craft chain: execute {@code recipeId} {@code crafts} times. Each
 * execution consumes its ingredients from the player's live inventory and places the result back
 * into it as real items. {@code needsFullGrid} steps only execute with a crafting table in reach.
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
