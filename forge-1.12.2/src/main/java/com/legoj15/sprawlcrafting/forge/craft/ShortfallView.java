package com.legoj15.sprawlcrafting.forge.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * The raw materials still needed to make one of a target item — the data behind the
 * "missing resources" gather screen. Raw-materials-only by design (no dependency-chain reveal).
 */
public final class ShortfallView {

    private final ItemStack target;
    private final boolean approximate;
    private final List<ItemDemand> demands;

    public ShortfallView(ItemStack target, boolean approximate, List<ItemDemand> demands) {
        this.target = target;
        this.approximate = approximate;
        this.demands = Collections.unmodifiableList(new ArrayList<ItemDemand>(demands));
    }

    public static ShortfallView unavailable() {
        return new ShortfallView(ItemStack.EMPTY, false, Collections.<ItemDemand>emptyList());
    }

    /** The item the player was trying to make. */
    public ItemStack target() {
        return target;
    }

    /** True if the search budget was hit during the walk, so the list may undercount (a UX caveat). */
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
