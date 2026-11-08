package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.RecipeBookContainer;

/**
 * Client tick watcher (render thread) that tells the server which crafting grid the player currently
 * has on screen — what the server needs to decide whether a deferred craft's final step can be handed
 * off to that grid. The server can't observe the 2×2 inventory screen itself.
 *
 * <p>Only sends while a job is active (which also gates it to SprawlCrafting servers: a modless server
 * never starts a job) and only on change, so an idle player generates no traffic.
 */
public final class CraftingScreenWatcher {

    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static boolean wasActive;
    private static boolean sawJob;
    private static ContainerScreen<?> lastContainer;

    private CraftingScreenWatcher() {
    }

    /** The last open container screen, or null — the gather list's ESC parent fallback. */
    public static ContainerScreen<?> lastContainerScreen() {
        return lastContainer;
    }

    public static void clientTick() {
        // Ungated: drives a queued gather-screen open (JEI path) regardless of job state.
        MissingIngredients.pollPendingOpen();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ContainerScreen) {
            lastContainer = (ContainerScreen<?>) minecraft.screen;
        }

        // When a craft job starts, flag the stale recipe-book preview ghost for clearing; the recipe
        // book consumes it on its next ghost render (RecipeBookGuiMixin).
        boolean hasJob = ClientJobTracker.hasActiveJob();
        if (hasJob && !sawJob) {
            RecipeBookGhost.requestClear();
        }
        sawJob = hasJob;

        boolean active = hasJob && minecraft.getConnection() != null;
        if (!active) {
            wasActive = false;
            return;
        }

        int width = 0;
        int height = 0;
        if (minecraft.screen instanceof ContainerScreen) {
            Container menu = ((ContainerScreen<?>) minecraft.screen).getMenu();
            // RecipeBookContainer also covers furnaces (1×1); the server-side ≥2×2 threshold filters those.
            if (menu instanceof RecipeBookContainer) {
                width = ((RecipeBookContainer<?>) menu).getGridWidth();
                height = ((RecipeBookContainer<?>) menu).getGridHeight();
            }
        }

        if (!wasActive || width != lastWidth || height != lastHeight) {
            wasActive = true;
            lastWidth = width;
            lastHeight = height;
            Services.PLATFORM.sendScreenState(width, height);
        }
    }

    /** Reset on disconnect so the next session re-syncs from scratch. */
    public static void reset() {
        lastWidth = -1;
        lastHeight = -1;
        wasActive = false;
        sawJob = false;
        lastContainer = null;
        RecipeBookGhost.reset();
    }

    /** Disconnect hook shared by both loaders: drop every piece of per-session client state. */
    public static void resetAllClientState() {
        ClientJobTracker.reset();
        reset();
        DeferredCraftableCache.invalidate();
        DeferredClickState.clear();
        MissingIngredients.reset();
    }
}
