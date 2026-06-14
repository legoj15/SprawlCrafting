package com.legoj15.sprawlcrafting.platform.neoforge;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entrypoint (a second {@code @Mod} for this id, targeting {@link Dist#CLIENT}),
 * so its references to client classes never load on a dedicated server. Handles client
 * disconnect to drop all per-session deferred-craft client state, preventing a stale progress
 * toast, a synced deferred-craftable set, or a pending preview from leaking into the next world.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class NeoForgeClient {

    public NeoForgeClient(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(NeoForgeClient::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(NeoForgeClient::onClientTick);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientJobTracker.reset();
        CraftingScreenWatcher.reset();       // drop the last-sent open-grid state
        DeferredCraftableCache.invalidate(); // drop the synced/solved craftable set
        DeferredClickState.clear();          // drop any pending click preview
        MissingIngredients.reset();          // drop any pending/cached gather list
    }

    // Tell the server which crafting grid is on screen (only while a job is active), so a
    // deferred craft's final step can be handed off to it for the player to grab.
    private static void onClientTick(ClientTickEvent.Post event) {
        CraftingScreenWatcher.clientTick();
    }
}
