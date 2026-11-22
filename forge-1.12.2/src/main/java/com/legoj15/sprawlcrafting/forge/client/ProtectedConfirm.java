package com.legoj15.sprawlcrafting.forge.client;

import net.minecraft.client.Minecraft;

/**
 * Click-again-to-confirm state for the JEI "+" button of a protected-only recipe (issue #53): a
 * plan only the player's protected (enchanted/renamed) stacks can make must not start on the
 * first click, whose only warning today is the hover tooltip. The first click arms the recipe —
 * the button turns red and says so — and the second click on the same recipe within
 * {@link #ARM_WINDOW_MS} sends the protected start; the arm expires after that window, and arming
 * a different recipe replaces it. A second click sooner than {@link #MIN_CONFIRM_MS} is taken as
 * an accidental double-click and keeps the arm instead of confirming.
 *
 * <p>JEI 4 has no per-error button colour API: the red tint rides {@code JeiTintState}, which
 * records each protected layout's recipe name and asks {@link #isArmed} with it, so the colour
 * uses the same key as the click. Window and keys mirror the modern and 1.16.5 copies.
 *
 * <p>Client thread only, like {@link DeferredClickState}, so plain static fields need no
 * synchronization.
 */
public final class ProtectedConfirm {

    /** How long an armed recipe stays confirmable before the button reverts to yellow. */
    public static final long ARM_WINDOW_MS = 3000;
    /** A confirm sooner than this after the arm is a double-click, not a deliberate second click. */
    public static final long MIN_CONFIRM_MS = 250;

    private static Object armedKey;
    private static long armedAtMillis;

    private ProtectedConfirm() {
    }

    /**
     * @param key the clicked recipe's identity ({@code IRecipe#getRegistryName})
     * @return true when this click confirms a live arm of the same recipe — the caller sends the
     *         protected start, exactly the call a pre-#53 single click made; false when it just
     *         armed {@code key} (replacing any other armed recipe), or came too soon after the arm,
     *         and nothing should be sent
     */
    public static boolean confirmOrArm(Object key) {
        if (isArmed(key)) {
            if (age() < MIN_CONFIRM_MS) {
                return false; // double-click: stay armed, keep the original window
            }
            clear();
            return true;
        }
        armedKey = key;
        armedAtMillis = Minecraft.getSystemTime();
        return false;
    }

    /**
     * True iff {@code key} equals the armed recipe and the arm has not expired; an expired arm (or
     * one the clock stepped back past) is cleared on the way so the next arm starts fresh.
     */
    public static boolean isArmed(Object key) {
        if (armedKey == null || !armedKey.equals(key)) {
            return false;
        }
        long age = age();
        if (age < 0 || age > ARM_WINDOW_MS) {
            clear();
            return false;
        }
        return true;
    }

    /** Disarms — the confirming click does this itself, and every start send does too (issue #53). */
    public static void clear() {
        armedKey = null;
        armedAtMillis = 0L;
    }

    private static long age() {
        return Minecraft.getSystemTime() - armedAtMillis;
    }
}
