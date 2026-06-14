package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.network.CraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.ShortfallPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class FabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Handler runs on the render thread (Fabric dispatches before invoking).
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressPayload.TYPE,
                (payload, context) -> ClientJobTracker.accept(payload));
        // 26.x: receive the server-computed deferred-craftable set + plan previews. 1.21.1 computes
        // these on the client, so it registers no receivers here.
        //? if >=1.21.11 {
        /*ClientPlayNetworking.registerGlobalReceiver(DeferredCraftableSyncPayload.TYPE,
                (payload, context) -> DeferredCraftableCache.accept(payload.displayIds(), payload.recipeIds()));
        ClientPlayNetworking.registerGlobalReceiver(CraftPreviewPayload.TYPE,
                (payload, context) -> DeferredClickState.acceptPreview(payload.displayId(), payload.lines()));
        ClientPlayNetworking.registerGlobalReceiver(ShortfallPayload.TYPE,
                (payload, context) -> MissingIngredients.accept(
                        payload.token(), payload.targetItem(), payload.targetCount(),
                        payload.approximate(), payload.demands()));*/
        //?}
        // Tell the server which crafting grid is on screen (only while a job is active), so a
        // deferred craft's final step can be handed off to it for the player to grab.
        ClientTickEvents.END_CLIENT_TICK.register(client -> CraftingScreenWatcher.clientTick());
        // Drop all per-session client state on disconnect so a stale toast, a synced
        // deferred-craftable set, or a pending preview can't leak into the next world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientJobTracker.reset();
            CraftingScreenWatcher.reset();
            DeferredCraftableCache.invalidate();
            DeferredClickState.clear();
            MissingIngredients.reset();
        });
    }
}
