package com.legoj15.sprawlcrafting.forge;

import com.legoj15.sprawlcrafting.forge.craft.CraftQueueManager;

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

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CraftQueueManager.clear(event.player.getUniqueID());
    }
}
