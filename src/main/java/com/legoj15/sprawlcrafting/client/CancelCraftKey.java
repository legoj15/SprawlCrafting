package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.network.CancelCraftPayload;
import com.legoj15.sprawlcrafting.platform.Services;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * The "Cancel deferred craft" key: sends {@link CancelCraftPayload} to stop the player's running
 * (or paused) job. Unbound by default — never a literal key code, which 26.3 renumbered — so it
 * only fires after the player binds it in Controls. The send is gated on an active job (nothing to
 * cancel otherwise) and on the server having negotiated the channel, so on an older or modless
 * server the key silently does nothing. The progress-toast click (issue #34) reuses the same
 * gate and send through {@link ToastCancelClick}.
 */
public final class CancelCraftKey {

    // The (name, key, category) constructor defaults to a keyboard key; naming the type would need its own
    // gate, since 26.3 renamed InputConstants.Type.KEYSYM to KEYBOARD.
    //? if >=1.21.11 {
    /*public static final KeyMapping KEY = new KeyMapping("key.sprawlcrafting.cancel_craft",
            InputConstants.UNKNOWN.getValue(), KeyMapping.Category.INVENTORY);*/
    //?} else {
    public static final KeyMapping KEY = new KeyMapping("key.sprawlcrafting.cancel_craft",
            InputConstants.UNKNOWN.getValue(), "key.categories.inventory");
    //?}

    private CancelCraftKey() {
    }

    /**
     * Polls the key each client tick. Queued clicks are drained first, so a held or double-pressed
     * key sends at most one cancel per tick.
     */
    public static void clientTick() {
        boolean clicked = false;
        while (KEY.consumeClick()) {
            clicked = true;
        }
        if (!clicked) {
            return;
        }
        if (canCancel()) {
            sendCancel();
        }
    }

    /** The gate both the key and the toast click share: a job to cancel and a server that can act on it. */
    public static boolean canCancel() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return ClientJobTracker.hasActiveJob() && listener != null
                && Services.PLATFORM.canSendToServer(listener.getConnection(), CancelCraftPayload.ID);
    }

    /** Sends the cancel request — only meaningful when {@link #canCancel()} passed. */
    public static void sendCancel() {
        Services.PLATFORM.sendToServer(CancelCraftPayload.INSTANCE);
    }
}
