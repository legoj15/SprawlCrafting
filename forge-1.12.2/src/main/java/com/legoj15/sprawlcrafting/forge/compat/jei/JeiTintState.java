package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.Map;
import java.util.WeakHashMap;

import com.legoj15.sprawlcrafting.forge.client.ProtectedConfirm;

import mezz.jei.api.gui.IRecipeLayout;

/**
 * Client-side side-band between the transfer handler's VALIDATION pass and the "+" button's draw:
 * how the button for a given recipe layout should present. JEI 4 has no API to color the transfer
 * button (that arrived in modern JEI), and the only state the button itself stores is the
 * validation error — not enough to distinguish a plain move from a deferred sprawl-craft. So the
 * handler records the classification here and the {@code GuiIconButtonSmallMixin} tints the icon
 * from it: {@link Tint#DEFERRED} yellow (multi-step craft), {@link Tint#GATHER} orange (unsolvable
 * — the click opens the missing-resources screen), {@link Tint#CONFIRM} red (an armed protected-only
 * recipe, issue #53, read live in {@link #get}), no entry = stock look (plain move).
 *
 * <p>Keyed weakly by the layout instance (the same object JEI passes to validation and stores on
 * the button), so entries vanish with the recipe page. Computed once per validation — never at
 * draw time: recipe classification involves the solver, and the button draws every frame. The
 * one exception is {@link Tint#CONFIRM}: the armed state of a protected-only recipe (issue #53)
 * expires on a clock, so it is read live in {@link #get} — an identity check against
 * {@code ProtectedConfirm}, no solver involved.
 * Everything here runs on the client thread only.
 */
public final class JeiTintState {

    /** Button presentation, mirroring the modern yellow/orange button states. */
    public enum Tint {
        DEFERRED,
        GATHER,
        /** Red (issue #53): the protected-only recipe is armed — the next click sends it. */
        CONFIRM
    }

    private static final Map<IRecipeLayout, Tint> TINTS = new WeakHashMap<IRecipeLayout, Tint>();
    /** Protected-only layouts' recipe names (#53): the red tint asks ProtectedConfirm with the click's key. */
    private static final Map<IRecipeLayout, Object> PROTECTED_KEYS = new WeakHashMap<IRecipeLayout, Object>();

    private JeiTintState() {
    }

    /** Records the layout's tint; null clears (a revalidation may flip a recipe back to direct). */
    static void put(IRecipeLayout layout, Tint tint) {
        PROTECTED_KEYS.remove(layout); // re-recorded by putProtectedKey when still protected-only
        if (tint == null) {
            TINTS.remove(layout);
        } else {
            TINTS.put(layout, tint);
        }
    }

    /** Records the protected-only recipe behind a DEFERRED layout; call after {@link #put}. */
    static void putProtectedKey(IRecipeLayout layout, Object recipeKey) {
        PROTECTED_KEYS.put(layout, recipeKey);
    }

    /** Object-typed so the mixin can pass the accessor's field value straight through. */
    public static Tint get(Object layout) {
        //noinspection SuspiciousMethodCalls -- identity-based weak map, the key IS an IRecipeLayout
        Tint stored = TINTS.get(layout);
        // Issue #53: an armed protected-only recipe reads red, checked live because the arm can
        // start or expire between two validations of the same layout. Only over the yellow
        // deferred tint: a revalidation that flipped the layout to plain or gather wins.
        Object key = stored == Tint.DEFERRED ? PROTECTED_KEYS.get(layout) : null;
        if (key != null && ProtectedConfirm.isArmed(key)) {
            return Tint.CONFIRM;
        }
        return stored;
    }
}
