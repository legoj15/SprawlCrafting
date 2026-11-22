package com.legoj15.sprawlcrafting.forge.client;

import java.lang.ref.WeakReference;

import net.minecraft.client.Minecraft;

/**
 * Click-again-to-confirm state for the JEI "+" button of a protected-only recipe (issue #53): a
 * plan only the player's protected (enchanted/renamed) stacks can make must not start on the
 * first click, whose only warning today is the hover tooltip. The first click arms the recipe —
 * the button turns red and says so — and the second click on the same recipe within
 * {@link #ARM_WINDOW_MS} sends the protected start; the arm expires after that window, and arming
 * a different recipe replaces it.
 *
 * <p>Unlike modern JEI, JEI 4 has no per-error button colour API: the tint rides the
 * {@code JeiTintState} side-band keyed by the recipe layout. So the arm also remembers the
 * layout it was clicked on ({@link #isArmedLayout}, identity compared — JEI hands the same
 * instance to validation and to the button) for the red confirm tint.
 *
 * <p>Client thread only, like {@link DeferredClickState}, so plain static fields need no
 * synchronization.
 */
public final class ProtectedConfirm {

    /** How long an armed recipe stays confirmable before the button reverts to yellow. */
    public static final long ARM_WINDOW_MS = 3000;

    private static Object armedKey;
    /** Weak, like JeiTintState's map: an armed layout must not outlive its recipe page. */
    private static WeakReference<Object> armedLayout;
    private static long armedAtMillis;

    private ProtectedConfirm() {
    }

    /**
     * @param key    the clicked recipe's identity ({@code IRecipe#getRegistryName})
     * @param layout the JEI recipe layout the click happened on (the tint side-band's key)
     * @return true when this click confirms a live arm of the same recipe — the caller sends the
     *         protected start, exactly the call a pre-#53 single click made; false when it just
     *         armed {@code key} (replacing any other armed recipe) and nothing should be sent
     */
    public static boolean confirmOrArm(Object key, Object layout) {
        if (isArmed(key)) {
            clear();
            return true;
        }
        armedKey = key;
        armedLayout = new WeakReference<Object>(layout);
        armedAtMillis = Minecraft.getSystemTime();
        return false;
    }

    /**
     * True iff {@code key} equals the armed recipe and the arm has not expired; an expired arm is
     * cleared on the way past so the next arm starts a fresh window.
     */
    public static boolean isArmed(Object key) {
        if (armedKey == null || !armedKey.equals(key)) {
            return false;
        }
        if (expired()) {
            clear();
            return false;
        }
        return true;
    }

    /**
     * True iff {@code layout} is the very layout object the arm was clicked on and the arm has
     * not expired — the button it tinted red is the one whose click confirms.
     */
    public static boolean isArmedLayout(Object layout) {
        if (armedLayout == null || armedLayout.get() != layout) {
            return false;
        }
        if (expired()) {
            clear();
            return false;
        }
        return true;
    }

    /** Disarms — the confirming click does this itself, and every plain start send does too (issue #53). */
    public static void clear() {
        armedKey = null;
        armedLayout = null;
        armedAtMillis = 0L;
    }

    private static boolean expired() {
        return Minecraft.getSystemTime() - armedAtMillis > ARM_WINDOW_MS;
    }
}
