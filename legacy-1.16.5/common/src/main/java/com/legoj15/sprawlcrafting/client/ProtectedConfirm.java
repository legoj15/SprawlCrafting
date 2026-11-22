package com.legoj15.sprawlcrafting.client;

import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * Click-again-to-confirm state for the JEI transfer button of a protected-only recipe (issue
 * #53): a plan only the player's protected (enchanted/renamed) stacks can make must not start on
 * the first click, whose only warning today is the hover tooltip. The first click arms the
 * recipe — the button turns red and says so — and the second click on the same recipe within
 * {@link #ARM_WINDOW_MS} sends the protected start; the arm expires after that window, and arming
 * a different recipe replaces it. Deliberately JEI-free (JEI is only a suggested dependency), so
 * the shared tooltip lines live here rather than in the JEI transfer handler.
 *
 * <p>Render thread only, like {@link DeferredClickState}, so plain static fields need no
 * synchronization.
 */
public final class ProtectedConfirm {

    /** How long an armed recipe stays confirmable before the button reverts to yellow. */
    public static final long ARM_WINDOW_MS = 3000;

    private static Object armedKey;
    private static long armedAtMillis;

    private ProtectedConfirm() {
    }

    /**
     * @param key the clicked recipe's identity ({@code IRecipe#getId})
     * @return true when this click confirms a live arm of the same recipe — the caller sends the
     *         protected start, exactly the call a pre-#53 single click made; false when it just
     *         armed {@code key} (replacing any other armed recipe) and nothing should be sent
     */
    public static boolean confirmOrArm(Object key) {
        if (isArmed(key)) {
            clear();
            return true;
        }
        armedKey = key;
        armedAtMillis = Util.getMillis();
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
        if (Util.getMillis() - armedAtMillis > ARM_WINDOW_MS) {
            clear();
            return false;
        }
        return true;
    }

    /** Disarms — the confirming click does this itself, and every plain start send does too (issue #53). */
    public static void clear() {
        armedKey = null;
        armedAtMillis = 0L;
    }

    /** Red line appended while armed: the next click spends the special items. */
    public static ITextComponent armedLine() {
        return new TranslationTextComponent("sprawlcrafting.recipe.protected.confirm_again")
                .withStyle(TextFormatting.RED);
    }

    /** Gold line appended while not armed: this button takes two clicks. */
    public static ITextComponent unarmedLine() {
        return new TranslationTextComponent("sprawlcrafting.recipe.protected.confirm_twice")
                .withStyle(TextFormatting.GOLD);
    }
}
