package com.legoj15.sprawlcrafting.forge;

import com.legoj15.sprawlcrafting.forge.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives the server-side craft engine: advances each player's active job at the end of their tick,
 * and clears a job when its player logs out. Registered on {@code MinecraftForge.EVENT_BUS} (which
 * carries {@code TickEvent} in 1.12.2).
 */
public final class ServerEvents {

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof EntityPlayerMP && !event.player.world.isRemote) {
            CraftQueueManager.tick((EntityPlayerMP) event.player);
        }
    }

    /** Announce the mod to the joining client, so it prefers server-backed paths over fallbacks. */
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            SprawlNetwork.sendHello((EntityPlayerMP) event.player);
        }
    }

    /**
     * Any server-processed container transition invalidates the client-reported crafting-screen
     * state: once the server has handled a close (or opened something else), a lingering "2x2
     * inventory screen open" report is definitively stale, and the safe default (no screen →
     * auto-craft) must win. This is what keeps the final-step hand-off from placing into a grid
     * the player already closed whenever the server can know that.
     */
    @SubscribeEvent
    public void onContainerOpen(net.minecraftforge.event.entity.player.PlayerContainerEvent.Open event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP) {
            ClientCraftingView.clearScreen(event.getEntityPlayer().getUniqueID());
        }
    }

    @SubscribeEvent
    public void onContainerClose(net.minecraftforge.event.entity.player.PlayerContainerEvent.Close event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP) {
            ClientCraftingView.clearScreen(event.getEntityPlayer().getUniqueID());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            // Before the (imminent) player save: rescue anything sitting in the non-persisted 2x2
            // craft matrix — a staged final craft, or items a crashed client never got to close out.
            CraftExecutor.returnInventoryGridOnLogout((EntityPlayerMP) event.player);
        }
        CraftQueueManager.clear(event.player.getUniqueID());
        ClientCraftingView.clear(event.player.getUniqueID());
    }
}
