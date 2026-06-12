package com.legoj15.sprawlcrafting.platform.neoforge;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
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
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientJobTracker.reset();
        DeferredCraftableCache.invalidate(); // drop the synced/solved craftable set
        DeferredClickState.clear();          // drop any pending click preview
    }
}
