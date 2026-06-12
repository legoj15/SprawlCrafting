package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.CommonClass;
import com.legoj15.sprawlcrafting.command.SprawlCraftingCommand;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.craft.DeferredCraftSync;
import com.legoj15.sprawlcrafting.network.CraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.RequestCraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftByDisplayPayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class FabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();

        c2s().register(
                StartDeferredCraftPayload.TYPE, StartDeferredCraftPayload.STREAM_CODEC);
        s2c().register(
                CraftProgressPayload.TYPE, CraftProgressPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftPayload.TYPE,
                (payload, context) -> CraftRequests.handleStartRequest(context.player(), payload.recipeId()));

        // 26.x server-driven recipe-book layer: the deferred-craftability sync + display-keyed
        // start + plan-preview round-trip. 1.21.1 computes all of this on the client (no payloads).
        //? if >=1.21.11 {
        /*c2s().register(
                StartDeferredCraftByDisplayPayload.TYPE, StartDeferredCraftByDisplayPayload.STREAM_CODEC);
        c2s().register(
                RequestCraftPreviewPayload.TYPE, RequestCraftPreviewPayload.STREAM_CODEC);
        s2c().register(
                DeferredCraftableSyncPayload.TYPE, DeferredCraftableSyncPayload.STREAM_CODEC);
        s2c().register(
                CraftPreviewPayload.TYPE, CraftPreviewPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftByDisplayPayload.TYPE,
                (payload, context) -> DeferredCraftSync.handleStartByDisplay(context.player(), payload.displayId()));
        ServerPlayNetworking.registerGlobalReceiver(RequestCraftPreviewPayload.TYPE,
                (payload, context) -> DeferredCraftSync.handlePreviewRequest(context.player(), payload.displayId()));*/
        //?}

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CraftQueueManager.tick(player);
                DeferredCraftSync.maybeSync(player); // no-op on 1.21.1
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SprawlCraftingCommand.register(dispatcher));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CraftQueueManager.clear(handler.getPlayer().getUUID());
            DeferredCraftSync.clear(handler.getPlayer().getUUID()); // no-op on 1.21.1
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CraftQueueManager.clearAll();
            DeferredCraftSync.clearAll(); // no-op on 1.21.1; prevents a cross-world debounce leak on 26.x
        });
    }

    // Fabric renamed the play-phase payload registries in 26.1.2 (playC2S/playS2C →
    // serverboundPlay/clientboundPlay). These gated accessors keep the registration calls above
    // loader-version-agnostic so the shared source compiles on both MC lines.
    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s() {
        //? if >=1.21.11 {
        /*return PayloadTypeRegistry.serverboundPlay();*/
        //?} else {
        return PayloadTypeRegistry.playC2S();
        //?}
    }

    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c() {
        //? if >=1.21.11 {
        /*return PayloadTypeRegistry.clientboundPlay();*/
        //?} else {
        return PayloadTypeRegistry.playS2C();
        //?}
    }
}
