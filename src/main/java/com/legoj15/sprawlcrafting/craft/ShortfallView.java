package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * The result of a gather-list query: the raw items the player still needs (with counts), the target
 * being made (id + count, for the screen title and icon), whether the underlying search hit its
 * budget so the list may be incomplete, and whether any count saturated at {@link Long#MAX_VALUE}
 * (the true figure is at least that; such rows read "more than …").
 *
 * <p>Built server-side by {@link CraftPlanner#shortfall} or, on 1.20.1/1.21.1, client-side; on 26.x it
 * is reconstructed on the client from {@code ShortfallV2Payload} (or the legacy {@code ShortfallPayload}
 * from a mod-1.0.3 server, whose counts are clamped ints and never saturated). The target is carried as
 * an id because the 26.x client cannot resolve a recipe to its result locally.
 */
public record ShortfallView(ResourceLocation targetItem, int targetCount, boolean approximate,
                            boolean saturated, List<ItemDemand> demands) {

    /** A placeholder for a recipe that could not be planned at all (target unknown). */
    public static ShortfallView unavailable() {
        return new ShortfallView(ResourceLocation.withDefaultNamespace("air"), 0, false, false, List.of());
    }

    public boolean isEmpty() {
        return demands.isEmpty();
    }
}
