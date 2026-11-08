package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * One line of a gather list: a quantity of a raw material the player still needs, with the full set
 * of interchangeable alternatives that would satisfy the slot it was gathered for (so the UI can
 * show "coal or charcoal", "any log"). {@code items.get(0)} is the representative the solver chose.
 *
 * <p>{@code count} is a {@code long}: the batched solver's gather lists run past
 * {@link Integer#MAX_VALUE} for compressed-block chains, and a count pinned at {@link Long#MAX_VALUE}
 * means "at least this many" ({@code CountFormat.isSaturated}). Computed client-side, never sent.
 */
public final class ItemDemand {

    private final List<ItemKey> items;
    private final long count;

    public ItemDemand(List<ItemKey> items, long count) {
        this.items = Collections.unmodifiableList(new ArrayList<ItemKey>(items));
        this.count = count;
    }

    public List<ItemKey> items() {
        return items;
    }

    /** The chosen representative item to display/count. */
    public ItemKey representative() {
        return items.get(0);
    }

    public long count() {
        return count;
    }

    /** A display stack of {@code count} of the representative (clamped to a single stack visually). */
    public ItemStack displayStack() {
        ItemStack stack = representative().toStack();
        stack.setCount((int) Math.max(1, Math.min(count, stack.getMaxStackSize())));
        return stack;
    }
}
