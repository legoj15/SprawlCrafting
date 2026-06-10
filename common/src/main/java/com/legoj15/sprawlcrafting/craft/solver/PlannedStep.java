package com.legoj15.sprawlcrafting.craft.solver;

/**
 * One entry in a solved craft plan: execute the recipe identified by {@code recipeId},
 * {@code crafts} times in a row. Consecutive identical recipes are merged by the solver.
 *
 * @param <R> recipe identifier type (ResourceLocation in game, String in tests)
 */
public record PlannedStep<R>(R recipeId, int crafts) {

    public PlannedStep {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
    }

    public PlannedStep<R> withCrafts(int newCrafts) {
        return new PlannedStep<>(recipeId, newCrafts);
    }
}
