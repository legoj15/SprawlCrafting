package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import com.google.common.collect.ImmutableList;

import net.minecraft.util.ResourceLocation;

/**
 * The result of a gather-list query: the raw items the player still needs (with counts), the target
 * being made (id + count, for the screen title and icon), and whether the underlying search hit its
 * budget so the list may be incomplete. 1.16.5 clients solve this locally (they have every recipe).
 */
public final class ShortfallView {

    private final ResourceLocation targetItem;
    private final int targetCount;
    private final boolean approximate;
    private final boolean saturated;
    private final List<ItemDemand> demands;

    public ShortfallView(ResourceLocation targetItem, int targetCount, boolean approximate, boolean saturated,
                         List<ItemDemand> demands) {
        this.targetItem = targetItem;
        this.targetCount = targetCount;
        this.approximate = approximate;
        this.saturated = saturated;
        this.demands = demands;
    }

    /** A placeholder for a recipe that could not be planned at all (target unknown). */
    public static ShortfallView unavailable() {
        return new ShortfallView(new ResourceLocation("minecraft", "air"), 0, false, false, ImmutableList.<ItemDemand>of());
    }

    /** True if some count saturated at {@link Long#MAX_VALUE} (that row reads "more than ..."). */
    public boolean saturated() {
        return saturated;
    }

    public ResourceLocation targetItem() {
        return targetItem;
    }

    public int targetCount() {
        return targetCount;
    }

    public boolean approximate() {
        return approximate;
    }

    public List<ItemDemand> demands() {
        return demands;
    }

    public boolean isEmpty() {
        return demands.isEmpty();
    }
}
