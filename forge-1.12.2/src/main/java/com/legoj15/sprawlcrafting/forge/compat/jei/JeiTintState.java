package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.Map;
import java.util.WeakHashMap;

import mezz.jei.api.gui.IRecipeLayout;

/**
 * Client-side side-band between the transfer handler's VALIDATION pass and the "+" button's draw:
 * how the button for a given recipe layout should present. JEI 4 has no API to color the transfer
 * button (that arrived in modern JEI), and the only state the button itself stores is the
 * validation error — not enough to distinguish a plain move from a deferred sprawl-craft. So the
 * handler records the classification here and the {@code GuiIconButtonSmallMixin} tints the icon
 * from it: {@link Tint#DEFERRED} yellow (multi-step craft), {@link Tint#GATHER} orange (unsolvable
 * — the click opens the missing-resources screen), no entry = stock look (plain move).
 *
 * <p>Keyed weakly by the layout instance (the same object JEI passes to validation and stores on
 * the button), so entries vanish with the recipe page. Computed once per validation — never at
 * draw time: recipe classification involves the solver, and the button draws every frame.
 * Everything here runs on the client thread only.
 */
public final class JeiTintState {

    /** Button presentation, mirroring the modern yellow/orange button states. */
    public enum Tint {
        DEFERRED,
        GATHER
    }

    private static final Map<IRecipeLayout, Tint> TINTS = new WeakHashMap<IRecipeLayout, Tint>();

    private JeiTintState() {
    }

    /** Records the layout's tint; null clears (a revalidation may flip a recipe back to direct). */
    static void put(IRecipeLayout layout, Tint tint) {
        if (tint == null) {
            TINTS.remove(layout);
        } else {
            TINTS.put(layout, tint);
        }
    }

    /** Object-typed so the mixin can pass the accessor's field value straight through. */
    public static Tint get(Object layout) {
        //noinspection SuspiciousMethodCalls -- identity-based weak map, the key IS an IRecipeLayout
        return TINTS.get(layout);
    }
}
