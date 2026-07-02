package com.legoj15.sprawlcrafting.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Client-side flag: is SprawlCrafting present on the server we're connected to? Set by the login
 * {@code ServerHelloMessage}, cleared on disconnect. The mod is joinable in both mixed setups
 * ({@code acceptableRemoteVersions="*"}), so client features that have a server-backed path and a
 * degraded client-only fallback (the JEI "+" grid fill) branch on this instead of assuming.
 */
public final class ServerPresence {

    private static volatile boolean present;

    /** True once the server has identified itself as running the mod (integrated server included). */
    public static boolean active() {
        return present;
    }

    public static void markPresent() {
        present = true;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        present = false;
        // The HUD flyout mirrors a job on the server we just left; drop it with the connection.
        // Cleared via the main-thread queue, not directly: this event fires on the netty thread,
        // where a progress update delivered just before the disconnect may already sit scheduled —
        // a direct clear here would run FIRST and the queued update would resurrect a phantom
        // "crafting…" card that nothing ever clears. FIFO ordering makes the clear run last.
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                ClientCraftState.clear();
            }
        });
    }
}
