package com.legoj15.sprawlcrafting.craft.solver;

import java.util.Objects;

/**
 * One entry in a solved craft plan: execute the recipe identified by {@code recipeId},
 * {@code crafts} times in a row. Consecutive identical recipes are merged by the solver.
 *
 * <p>Authored in Java 8 (a plain final class, not a record) because this module is the single
 * source of truth shared with the legacy-Forge 1.12.2 build, whose runtime is Java 8 and cannot
 * load record bytecode. The hand-written accessors / {@code equals} / {@code hashCode} reproduce
 * the record contract the modern callers and the test suite rely on.
 *
 * @param <R> recipe identifier type (ResourceLocation in game, String in tests)
 */
public final class PlannedStep<R> {

    private final R recipeId;
    private final int crafts;

    public PlannedStep(R recipeId, int crafts) {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        this.recipeId = recipeId;
        this.crafts = crafts;
    }

    public R recipeId() {
        return recipeId;
    }

    public int crafts() {
        return crafts;
    }

    public PlannedStep<R> withCrafts(int newCrafts) {
        return new PlannedStep<>(recipeId, newCrafts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlannedStep)) {
            return false;
        }
        PlannedStep<?> other = (PlannedStep<?>) o;
        return crafts == other.crafts && Objects.equals(recipeId, other.recipeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipeId, crafts);
    }

    @Override
    public String toString() {
        return "PlannedStep[recipeId=" + recipeId + ", crafts=" + crafts + "]";
    }
}
