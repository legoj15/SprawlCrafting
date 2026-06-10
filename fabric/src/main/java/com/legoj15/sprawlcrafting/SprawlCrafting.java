package com.legoj15.sprawlcrafting;

import com.legoj15.sprawlcrafting.command.SprawlCraftingCommand;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class SprawlCrafting implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();

        PayloadTypeRegistry.playC2S().register(
                StartDeferredCraftPayload.TYPE, StartDeferredCraftPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftPayload.TYPE,
                (payload, context) -> CraftRequests.handleStartRequest(context.player(), payload.recipeId()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CraftQueueManager.tick(player);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SprawlCraftingCommand.register(dispatcher));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CraftQueueManager.clear(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> CraftQueueManager.clearAll());
    }
}
