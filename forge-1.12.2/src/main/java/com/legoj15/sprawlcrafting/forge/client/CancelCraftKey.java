package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.input.Keyboard;

/**
 * The "Cancel deferred craft" keybind (issue #31): sends the v2 cancel request for the job the
 * flyout currently shows. Unbound by default ({@link Keyboard#KEY_NONE}) — a cancel is rare, and
 * a default binding would fight existing saves' bindings — so players give it a key in Controls
 * (Inventory category). Reached only through {@code ClientProxy} (keybind registration + this bus
 * handler), which is what keeps the client-only {@link KeyBinding} out of common code. The
 * progress-toast click (issue #34) reuses the same gate and send through {@link ToastCancelClick}.
 */
public final class CancelCraftKey {

    public static final KeyBinding KEY = new KeyBinding(
            "key.sprawlcrafting.cancel_craft", Keyboard.KEY_NONE, "key.categories.inventory");

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Drain the press counter, but cancel at most once per tick: the request cancels the
        // whole job, so extra sends would only race the CANCELLED sync that follows.
        boolean pressed = false;
        while (KEY.isPressed()) {
            pressed = true;
        }
        if (pressed && canCancel()) {
            sendCancel();
        }
    }

    /**
     * The gate both the keybind and the toast click share. Double gate: the capability bit (an
     * older v2 server knows the channel but not the discriminator — an unknown one is a kick, see
     * SprawlNetwork's version-skew invariant) and a job actually shown as running or paused (a
     * terminal flyout's job is already gone).
     */
    public static boolean canCancel() {
        return ServerPresence.cancelCraft() && ClientCraftState.hasActiveJob();
    }

    /** Sends the cancel request — only meaningful when {@link #canCancel()} passed. */
    public static void sendCancel() {
        SprawlNetwork.cancelCraft();
    }
}
