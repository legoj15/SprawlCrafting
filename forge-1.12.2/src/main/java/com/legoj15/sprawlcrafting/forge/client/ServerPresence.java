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
    private static volatile boolean networkTerminals;
    private static volatile boolean cancelCraft;
    private static volatile boolean protectedCraft;
    private static volatile boolean networkProtected;

    /** True once the server has identified itself as running the mod (integrated server included). */
    public static boolean active() {
        return present;
    }

    /** True when the server's engine also counts an open AE2 Crafting Terminal's network (see the hello's capability byte). */
    public static boolean networkTerminals() {
        return present && networkTerminals;
    }

    /** True when the server also accepts the cancel-craft request (see the hello's capability byte). */
    public static boolean cancelCraft() {
        return present && cancelCraft;
    }

    /**
     * True when the server also accepts the protected-craft start (issue #40, see the hello's
     * capability byte): without it the client must not offer protected crafts at all — the server
     * would plan the plain pool only and refuse the request (and could not even receive it: an
     * older v2 server knows CHANNEL2 without that discriminator).
     */
    public static boolean protectedCraft() {
        return present && protectedCraft;
    }

    /**
     * True when the server also counts an open terminal network's protected stacks in the
     * protected tier (issue #49, see the hello's capability bit): without it the client must not
     * count them either — it would offer protected crafts a matching-but-older server refuses to
     * start, since that server's engine never plans or picks network protected stacks.
     */
    public static boolean networkProtected() {
        return present && networkProtected;
    }

    public static void markPresent(boolean countsNetworkTerminals, boolean acceptsCancel,
                                   boolean acceptsProtected, boolean countsNetworkProtected) {
        networkTerminals = countsNetworkTerminals;
        cancelCraft = acceptsCancel;
        protectedCraft = acceptsProtected;
        networkProtected = countsNetworkProtected;
        present = true;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        present = false;
        networkTerminals = false;
        cancelCraft = false;
        protectedCraft = false;
        networkProtected = false;
        // The HUD flyout mirrors a job on the server we just left; drop it with the connection.
        // Cleared via the main-thread queue, not directly: this event fires on the netty thread,
        // where a progress update delivered just before the disconnect may already sit scheduled —
        // a direct clear here would run FIRST and the queued update would resurrect a phantom
        // "crafting…" card that nothing ever clears. FIFO ordering makes the clear run last.
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                ClientCraftState.clear();
                EngineWatchdog.reset();
                ToastCancelClick.clear(); // the toast's clickable rectangle dies with the job state
            }
        });
    }
}
