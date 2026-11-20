package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;

/**
 * The "Cancel deferred craft" keybind (issue #31). Unbound by default: cancelling is a recovery
 * action, not something worth a slot every player has to give up, and an unbound default keeps it out
 * of the way until wanted in Controls. Polled every client tick; a press only sends when the tracker
 * shows a live job (running or paused) and the server has the cancel channel — with nothing to
 * cancel, or on a server that cannot act on it, the press is a silent no-op.
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
        // Drain the click queue however many presses queued up, but send at most one request per tick:
        // the server cancels the whole job, and the CANCELLED snapshot it sends back clears
        // hasActiveJob() before the next tick could send a duplicate.
        boolean pressed = false;
        while (KEY.consumeClick()) {
            pressed = true;
        }
        if (pressed && ClientJobTracker.hasActiveJob() && Services.PLATFORM.canCancelCraft()) {
            Services.PLATFORM.sendCancelCraft();
        }
    }
}
