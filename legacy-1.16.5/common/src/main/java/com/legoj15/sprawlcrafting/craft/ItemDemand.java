package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import net.minecraft.util.ResourceLocation;

/**
 * One line of a "gather list": the set of items that satisfy a missing slot (e.g. coal-or-charcoal,
 * any log) and how many the player still needs. {@code items} is the full ordered alternative set so
 * the UI can cycle through them like the recipe book; {@code items.get(0)} is the representative.
 *
 * <p>{@code count} is a {@code long}: the batched solver's gather lists run past
 * {@link Integer#MAX_VALUE} for compressed-block chains, and a count pinned at {@link Long#MAX_VALUE}
 * means "at least this many" ({@code CountFormat.isSaturated}). Computed client-side, never sent.
 */
public final class ItemDemand {

    private final List<ResourceLocation> items;
    private final long count;

    public ItemDemand(List<ResourceLocation> items, long count) {
        this.items = items;
        this.count = count;
    }

    public List<ResourceLocation> items() {
        return items;
    }

    public long count() {
        return count;
    }
}
