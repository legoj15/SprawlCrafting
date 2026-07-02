package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Tells the server when the 2x2 inventory crafting screen is open — the one crafting surface the
 * server cannot observe (see {@code CraftingScreenStateMessage}) — so the final-step grid hand-off
 * knows whether the player is actually looking at that grid.
 *
 * <p>Reports only while a deferred craft is RUNNING: that is the only time the server consults the
 * view, and it self-gates the optional channel — a modless server never starts a job, so it is
 * never sent packets it wouldn't understand. State is re-sent from scratch at each job start
 * (the server's view may be stale from a previous job) and then only on change, so this costs a
 * handful of packets per job.
 */
public final class CraftingScreenWatcher {

    private static final int NONE = 0;
    private static final int INVENTORY_2X2 = 1;

    private boolean watching;
    private int lastSent = -1;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // terminalSinceMs is non-zero for every terminal state (finished/cancelled/ready-in-grid),
        // so this is exactly "a job is live on the server right now".
        boolean jobRunning = ClientCraftState.active && ClientCraftState.terminalSinceMs == 0L;
        if (!jobRunning) {
            // Parting correction: if the last report said "2x2 open", retract it as the watching
            // window closes, so the server's view doesn't stay stale between jobs. Still
            // job-gated (a job WAS just live, so the server speaks the channel); skipped when the
            // connection itself is gone.
            if (watching && lastSent == INVENTORY_2X2 && ServerPresence.active()
                    && Minecraft.getMinecraft().getConnection() != null) {
                SprawlNetwork.sendScreenState(0, 0);
            }
            watching = false;
            return;
        }
        if (!watching) {
            watching = true;
            lastSent = -1;
        }
        int state = Minecraft.getMinecraft().currentScreen instanceof GuiInventory
                ? INVENTORY_2X2 : NONE;
        if (state != lastSent) {
            lastSent = state;
            if (state == INVENTORY_2X2) {
                SprawlNetwork.sendScreenState(2, 2);
            } else {
                SprawlNetwork.sendScreenState(0, 0);
            }
        }
    }
}
