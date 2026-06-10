package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;

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
    private static boolean toastActive;

    private ClientJobTracker() {
    }

    public static void accept(CraftProgressPayload payload) {
        boolean wasTerminal = current != null && current.state().isTerminal();
        if (payload.state().isTerminal() && !(wasTerminal)) {
            terminalAtMillis = Util.getMillis();
        }
        current = payload;
        if (!toastActive) {
            toastActive = true;
            Minecraft.getInstance().getToasts().addToast(new CraftProgressToast());
        }
    }

    /** Latest snapshot, or null when there is nothing to show. */
    public static CraftProgressPayload current() {
        return current;
    }

    /** True once a terminal snapshot has been on screen long enough to slide out. */
    public static boolean terminalDisplayElapsed() {
        return current != null && current.state().isTerminal()
                && Util.getMillis() - terminalAtMillis > TERMINAL_DISPLAY_MS;
    }

    /** Called by the toast as it hides; the next update will spawn a fresh toast. */
    public static void onToastHidden() {
        toastActive = false;
        if (current != null && current.state().isTerminal()) {
            current = null;
        }
    }
}
