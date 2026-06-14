package com.legoj15.sprawlcrafting.client;

/**
 * One-shot request to clear the vanilla recipe-book preview ghost (the faded ingredients painted over
 * the crafting grid). A deferred craft started from JEI/REI or the recipe book leaves a stale red — or
 * yellow — preview ghost that vanilla never clears on its own: {@code GhostRecipe}/{@code GhostSlots}
 * render unconditionally on top of the real items the craft deposits, and vanilla only clears on a grid
 * slot click or a re-click of a different recipe, none of which a deferred craft performs.
 *
 * <p>{@link CraftingScreenWatcher} requests a clear when a craft job starts; {@code
 * RecipeBookComponentMixin} consumes it the next time the recipe book renders its ghost — i.e. the next
 * time the player is actually at a crafting screen. That deferral matters because the craft is often
 * started from a JEI/REI viewer that's still frontmost; the flag survives the detour and the ghost is
 * cleared the moment the player returns to the table, before they can see it over the finished items.
 */
public final class RecipeBookGhost {

    private static boolean clearPending;

    private RecipeBookGhost() {
    }

    /** Flag the stale preview ghost to be cleared on the recipe book's next ghost render. */
    public static void requestClear() {
        clearPending = true;
    }

    /** Returns true at most once per request, consuming it; the caller then clears its ghost. */
    public static boolean consumeClear() {
        if (clearPending) {
            clearPending = false;
            return true;
        }
        return false;
    }

    /** Drop any pending request — call on disconnect. */
    public static void reset() {
        clearPending = false;
    }
}
