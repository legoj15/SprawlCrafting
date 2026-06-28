package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.network.CraftingScreenStatePayload;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=1.21.11 {
/*import net.minecraft.world.inventory.AbstractCraftingMenu;*/
//?} else {
import net.minecraft.world.inventory.RecipeBookMenu;
//?}

/**
 * Client tick watcher (render thread) that tells the server which crafting grid the player
 * currently has on screen — the dimension the server needs to decide whether a deferred
 * craft's final step can be handed off to that grid for the player to grab. The server can't
 * observe the 2×2 inventory screen itself (see {@link CraftingScreenStatePayload}).
 *
 * <p>On 1.21.1 it only sends while a job is active ({@link ClientJobTracker}): this gates the
 * optional channel to SprawlCrafting servers (a modless server never starts a job, so the send
 * can't throw) and keeps it to a few packets per job. On 26.x it reports continuously — on every
 * crafting-screen open/close — gated instead by {@code IPlatformHelper.canSendToServer} (so
 * modless servers never see it), because the server also uses the open/closed signal to gate its
 * deferred-craftable reclassification ({@code DeferredCraftSync.maybeSync}). Either way it only
 * sends on change (the open grid's dimensions differ from the last send), so an idle player
 * generates no traffic.
 */
public final class CraftingScreenWatcher {

    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static boolean wasActive;
    private static boolean sawJob;
    private static AbstractContainerScreen<?> lastContainer;

    private CraftingScreenWatcher() {
    }

    /**
     * The last open container (crafting-table/inventory) screen, or null. The gather list anchors its
     * parent to this so ESC returns to the table even when a viewer (REI keeps its recipe screen open
     * after a transfer) is frontmost at open time — otherwise the gather screen and the viewer can
     * mutually return to each other, an inescapable ESC/E loop.
     */
    public static AbstractContainerScreen<?> lastContainerScreen() {
        return lastContainer;
    }

    public static void clientTick() {
        // Ungated: drives a queued gather-screen open (JEI path) regardless of job state.
        MissingIngredients.pollPendingOpen();

        Minecraft minecraft = Minecraft.getInstance();

        // Remember the container screen for the gather-list parent fallback (see lastContainerScreen).
        if (minecraft.screen instanceof AbstractContainerScreen<?> containerScreen) {
            lastContainer = containerScreen;
        }

        // When a craft job starts, flag the stale recipe-book preview ghost for clearing. The recipe
        // book clears it on its next ghost render (RecipeBookComponentMixin) — whenever the player is
        // next at a crafting screen — so a JEI/REI viewer detour in between doesn't drop the clear.
        boolean hasJob = ClientJobTracker.hasActiveJob();
        if (hasJob && !sawJob) {
            RecipeBookGhost.requestClear();
        }
        sawJob = hasJob;

        //? if >=1.21.11 {
        /*// 26.x: report the open crafting grid continuously (not only during jobs) so the server can
        // gate its deferred-craftable reclassification to players who actually have a recipe screen
        // open. canSendToServer keeps this off vanilla/modless servers, which never negotiate the
        // channel (so a job-gate is no longer needed for that). 1.21.1 has no server sync, so it
        // keeps the original job-gated reporting below (used only for the final-step hand-off).
        net.minecraft.client.multiplayer.ClientPacketListener listener = minecraft.getConnection();
        boolean active = listener != null
                && Services.PLATFORM.canSendToServer(listener.getConnection(), CraftingScreenStatePayload.TYPE.id());*/
        //?} else {
        boolean active = hasJob && minecraft.getConnection() != null;
        //?}
        if (!active) {
            wasActive = false;
            return;
        }

        int width = 0;
        int height = 0;
        if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
            AbstractContainerMenu menu = screen.getMenu();
            //? if >=1.21.11 {
            /*if (menu instanceof AbstractCraftingMenu craftingMenu) {
                width = craftingMenu.getGridWidth();
                height = craftingMenu.getGridHeight();
            }*/
            //?} else {
            // RecipeBookMenu also covers furnaces (1×1); the server-side ≥2×2 threshold filters those out.
            if (menu instanceof RecipeBookMenu<?, ?> craftingMenu) {
                width = craftingMenu.getGridWidth();
                height = craftingMenu.getGridHeight();
            }
            //?}
        }

        if (!wasActive || width != lastWidth || height != lastHeight) {
            wasActive = true;
            lastWidth = width;
            lastHeight = height;
            Services.PLATFORM.sendToServer(new CraftingScreenStatePayload(width, height));
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
}
