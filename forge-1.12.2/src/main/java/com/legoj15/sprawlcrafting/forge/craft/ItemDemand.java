package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * One line of a gather list: a quantity of a raw material the player still needs, with the full set
 * of interchangeable alternatives that would satisfy the slot it was gathered for (so the UI can
 * show "coal or charcoal", "any log"). {@code items.get(0)} is the representative the solver chose.
 */
public final class ItemDemand {

    private final List<ItemKey> items;
    private final int count;

    public ItemDemand(List<ItemKey> items, int count) {
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

    public int count() {
        return count;
    }

    /** A display stack of {@code count} of the representative (clamped to a single stack visually). */
    public ItemStack displayStack() {
        ItemStack stack = representative().toStack();
        stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
        return stack;
    }
}
