package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;

/**
 * Render-thread-only holder of the latest {@link CraftProgressPayload} snapshot, and the
 * lifecycle owner of the single {@link CraftProgressToast}: a toast is added when a job
 * becomes visible and reused for every update until it hides itself (terminal state
 * displayed for a moment, or state gone).
 */
public final class ClientJobTracker {

    /** How long a finished/cancelled toast lingers before sliding out. */
    public static final long TERMINAL_DISPLAY_MS = 2500;

    private static CraftProgressPayload current;
    private static long terminalAtMillis;

    private ClientJobTracker() {
    }

    public static void accept(CraftProgressPayload payload) {
        boolean wasTerminal = current != null && current.state().isTerminal();
        if (payload.state().isTerminal() && !(wasTerminal)) {
            terminalAtMillis = Util.getMillis();
        }
        current = payload;
        // Presence check rather than a local flag: vanilla clears all toasts on
        // disconnect without our toast ever returning HIDE, so a flag would desync and
        // permanently suppress future toasts. Asking ToastComponent directly cannot.
        if (Minecraft.getInstance().getToasts().getToast(CraftProgressToast.class, Toast.NO_TOKEN) == null) {
            Minecraft.getInstance().getToasts().addToast(new CraftProgressToast());
        }
    }

    /** Clears all job state — call on client disconnect so nothing leaks into the next world. */
    public static void reset() {
        current = null;
        terminalAtMillis = 0;
    }

    /** Latest snapshot, or null when there is nothing to show. */
    public static CraftProgressPayload current() {
        return current;
    }

    /** True while a job is running or paused — used to gate new craft offers in UIs. */
    public static boolean hasActiveJob() {
        return current != null && !current.state().isTerminal();
    }

    /** True once a terminal snapshot has been on screen long enough to slide out. */
    public static boolean terminalDisplayElapsed() {
        return current != null && current.state().isTerminal()
                && Util.getMillis() - terminalAtMillis > TERMINAL_DISPLAY_MS;
    }

    /** Called by the toast as it hides; the next update will spawn a fresh toast. */
    public static void onToastHidden() {
        if (current != null && current.state().isTerminal()) {
            current = null;
        }
    }
}
