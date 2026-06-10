package com.legoj15.sprawlcrafting;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class SprawlCraftingClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Handler runs on the render thread (Fabric dispatches before invoking).
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressPayload.TYPE,
                (payload, context) -> ClientJobTracker.accept(payload));
    }
}
