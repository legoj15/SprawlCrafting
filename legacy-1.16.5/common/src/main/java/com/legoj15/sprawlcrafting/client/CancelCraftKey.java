package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;

/**
 * The "Cancel deferred craft" keybind (issue #31). Unbound by default: cancelling is a recovery
 * action, not something worth a slot every player has to give up, and an unbound default keeps it out
 * of the way until wanted in Controls. Polled every client tick; a press only sends when the tracker
 * shows a live job (running or paused) and the server has the cancel channel — with nothing to
 * cancel, or on a server that cannot act on it, the press is a silent no-op. The progress-toast
 * click (issue #34) reuses the same gate and send through {@link ToastCancelClick}.
 */
public final class CancelCraftKey {

    public static final KeyBinding KEY = new KeyBinding(
            "key.sprawlcrafting.cancel_craft",
            InputMappings.Type.KEYSYM,
            InputMappings.UNKNOWN.getValue(), // unbound: the player picks the key in Controls
            "key.categories.inventory");

    private CancelCraftKey() {
    }

    public static void clientTick() {
        // Drain the click queue however many presses queued up, but send at most one request per tick.
        // A second send while the CANCELLED snapshot is still in flight is harmless: the server finds
        // no job and does nothing.
        boolean pressed = false;
        while (KEY.consumeClick()) {
            pressed = true;
        }
        if (pressed && canCancel()) {
            sendCancel();
        }
    }

    /** The gate both the keybind and the toast click share: a live job and a server that can cancel it. */
    public static boolean canCancel() {
        return ClientJobTracker.hasActiveJob() && Services.PLATFORM.canCancelCraft();
    }

    /** Sends the cancel request — only meaningful when {@link #canCancel()} passed. */
    public static void sendCancel() {
        Services.PLATFORM.sendCancelCraft();
    }
}
