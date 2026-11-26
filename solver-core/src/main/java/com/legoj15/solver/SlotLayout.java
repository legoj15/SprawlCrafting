package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One run of a planned step's per-slot item choice: the next {@link #crafts()} crafts of that step
 * put {@code items().get(i)} into non-empty slot {@code i} of the recipe — the order of
 * {@code RecipeInfo.ingredientSlots()} with blank cells removed and a slot of quantity {@code q}
 * (see {@code RecipeInfo.ingredientSlotCount}) listed {@code q} times (the order
 * {@code PlanSimulator.nonEmptySlots(recipe)} returns), which is what {@link SlotAssignment#assign}
 * is handed too. Every entry is one of that slot's alternatives; the solver's passes decide the
 * items when they plan, so an executor that follows the layouts never re-decides and eats stock a
 * later step counted on (#23).
 *
 * <p>Authored in Java 8 (a plain final class, not a record) for the same reason as
 * {@link PlannedStep}: this module is bundled by the legacy-Forge 1.12.2 build, whose runtime is
 * Java 8 and cannot load record bytecode.
 *
 * @param <K> item identity type
 */
public final class SlotLayout<K> {

    private final long crafts;
    private final List<K> items;

    /**
     * @param crafts how many consecutive crafts of the step use this layout (>= 1)
     * @param items  one item per non-empty ingredient slot, in slot order (no null entries)
     */
    public SlotLayout(long crafts, List<K> items) {
        if (crafts < 1) {
            throw new IllegalArgumentException("crafts must be >= 1, got " + crafts);
        }
        List<K> copy = new ArrayList<>(items.size());
        for (K item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not hold null (slot left unfilled)");
            }
            copy.add(item);
        }
        this.crafts = crafts;
        this.items = Collections.unmodifiableList(copy);
    }

    public long crafts() {
        return crafts;
    }

    /** Unmodifiable; one entry per non-empty ingredient slot of the recipe, in slot order. */
    public List<K> items() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SlotLayout)) {
            return false;
        }
        SlotLayout<?> other = (SlotLayout<?>) o;
        return crafts == other.crafts && Objects.equals(items, other.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(crafts, items);
    }

    @Override
    public String toString() {
        return "SlotLayout[crafts=" + crafts + ", items=" + items + "]";
    }
}
