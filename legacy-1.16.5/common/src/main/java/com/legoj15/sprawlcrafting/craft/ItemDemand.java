package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import net.minecraft.util.ResourceLocation;

/**
 * One line of a "gather list": the set of items that satisfy a missing slot (e.g. coal-or-charcoal,
 * any log) and how many the player still needs. {@code items} is the full ordered alternative set so
 * the UI can cycle through them like the recipe book; {@code items.get(0)} is the representative.
 */
public final class ItemDemand {

    private final List<ResourceLocation> items;
    private final int count;

    public ItemDemand(List<ResourceLocation> items, int count) {
        this.items = items;
        this.count = count;
    }

    public List<ResourceLocation> items() {
        return items;
    }

    public int count() {
        return count;
    }
}
