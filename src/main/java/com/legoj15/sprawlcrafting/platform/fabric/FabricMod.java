package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.CommonClass;
import com.legoj15.sprawlcrafting.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.craft.DeferredCraftSync;
import com.legoj15.sprawlcrafting.network.CancelCraftPayload;
import com.legoj15.sprawlcrafting.network.CraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePayload;
import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.RequestCraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.RequestShortfallByDisplayPayload;
import com.legoj15.sprawlcrafting.network.RequestShortfallByRecipePayload;
import com.legoj15.sprawlcrafting.network.ShortfallPayload;
import com.legoj15.sprawlcrafting.network.ShortfallV2Payload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftByDisplayPayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;
import com.legoj15.sprawlcrafting.network.StartProtectedCraftByDisplayPayload;
import com.legoj15.sprawlcrafting.network.StartProtectedCraftPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.5 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
//?}

public class FabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();

        //? if >=1.20.5 {
        c2s().register(
                StartDeferredCraftPayload.TYPE, StartDeferredCraftPayload.STREAM_CODEC);
        c2s().register(
                CraftingScreenStatePayload.TYPE, CraftingScreenStatePayload.STREAM_CODEC);
        c2s().register(
                CancelCraftPayload.TYPE, CancelCraftPayload.STREAM_CODEC);
        s2c().register(
                CraftProgressPayload.TYPE, CraftProgressPayload.STREAM_CODEC);
        // Capability marker only (never sent): the client's receiver tells the server it decodes PAUSED_STATION.
        s2c().register(
                com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload.TYPE,
                com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftPayload.TYPE,
                (payload, context) -> CraftRequests.handleStartRequest(context.player(), payload.recipeId()));
        ServerPlayNetworking.registerGlobalReceiver(CraftingScreenStatePayload.TYPE,
                (payload, context) -> ClientCraftingView.update(
                        context.player().getUUID(), payload.gridWidth(), payload.gridHeight()));
        ServerPlayNetworking.registerGlobalReceiver(CancelCraftPayload.TYPE,
                (payload, context) -> CraftQueueManager.cancel(context.player()));
        // The protected start (#40): the confirmed request whose plan may count the player's
        // protected stacks. Registered like the plain one; a client that never registered it is
        // never offered protected crafts.
        c2s().register(
                StartProtectedCraftPayload.TYPE, StartProtectedCraftPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartProtectedCraftPayload.TYPE,
                (payload, context) -> CraftRequests.handleStartRequest(context.player(), payload.recipeId(), true));
        //?} else {
        /*// Pre-1.20.5 Fabric networking: channel id + raw buffer, and receivers run on the netty
        // thread — decode there, then hop to the server thread (the 1.20.5+ API does that for us).
        // S2C channels need no registration; the client registers its receiver (FabricClient).
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    StartDeferredCraftPayload payload = StartDeferredCraftPayload.read(buf);
                    server.execute(() -> CraftRequests.handleStartRequest(player, payload.recipeId()));
                });
        ServerPlayNetworking.registerGlobalReceiver(CraftingScreenStatePayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    CraftingScreenStatePayload payload = CraftingScreenStatePayload.read(buf);
                    server.execute(() -> ClientCraftingView.update(
                            player.getUUID(), payload.gridWidth(), payload.gridHeight()));
                });
        // Nothing to decode, but the netty-thread hop is still needed before touching the queue.
        ServerPlayNetworking.registerGlobalReceiver(CancelCraftPayload.ID,
                (server, player, handler, buf, responseSender) ->
                        server.execute(() -> CraftQueueManager.cancel(player)));
        // The protected start (#40) on its own raw channel, like every payload here: an older
        // server that never registered it never receives one (the client's canSend gate keeps it
        // that way).
        ServerPlayNetworking.registerGlobalReceiver(StartProtectedCraftPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    StartProtectedCraftPayload payload = StartProtectedCraftPayload.read(buf);
                    server.execute(() -> CraftRequests.handleStartRequest(player, payload.recipeId(), true));
                });*/
        //?}

        // 26.x server-driven recipe-book layer: the deferred-craftability sync + display-keyed
        // start + plan-preview round-trip. 1.21.1 computes all of this on the client (no payloads).
        //? if >=1.21.11 {
        /*c2s().register(
                StartDeferredCraftByDisplayPayload.TYPE, StartDeferredCraftByDisplayPayload.STREAM_CODEC);
        c2s().register(
                RequestCraftPreviewPayload.TYPE, RequestCraftPreviewPayload.STREAM_CODEC);
        s2c().register(
                DeferredCraftableSyncPayload.TYPE, DeferredCraftableSyncPayload.STREAM_CODEC);
        // The protected-only subset of that set (#40), pushed right after it (FabricClient receives).
        s2c().register(
                ProtectedCraftableSyncPayload.TYPE, ProtectedCraftableSyncPayload.STREAM_CODEC);
        s2c().register(
                CraftPreviewPayload.TYPE, CraftPreviewPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftByDisplayPayload.TYPE,
                (payload, context) -> DeferredCraftSync.handleStartByDisplay(context.player(), payload.displayId()));
        // The protected by-display start (#40), the recipe book's confirm for a protected-only entry.
        c2s().register(
                StartProtectedCraftByDisplayPayload.TYPE, StartProtectedCraftByDisplayPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartProtectedCraftByDisplayPayload.TYPE,
                (payload, context) -> DeferredCraftSync.handleStartByDisplay(
                        context.player(), payload.displayId(), true));
        ServerPlayNetworking.registerGlobalReceiver(RequestCraftPreviewPayload.TYPE,
                (payload, context) -> DeferredCraftSync.handlePreviewRequest(context.player(), payload.displayId()));
        c2s().register(
                RequestShortfallByDisplayPayload.TYPE, RequestShortfallByDisplayPayload.STREAM_CODEC);
        c2s().register(
                RequestShortfallByRecipePayload.TYPE, RequestShortfallByRecipePayload.STREAM_CODEC);
        s2c().register(
                ShortfallPayload.TYPE, ShortfallPayload.STREAM_CODEC);
        s2c().register(
                ShortfallV2Payload.TYPE, ShortfallV2Payload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RequestShortfallByDisplayPayload.TYPE,
                (payload, context) -> DeferredCraftSync.handleShortfallByDisplay(
                        context.player(), payload.token(), payload.displayId()));
        ServerPlayNetworking.registerGlobalReceiver(RequestShortfallByRecipePayload.TYPE,
                (payload, context) -> DeferredCraftSync.handleShortfallByRecipe(
                        context.player(), payload.token(), payload.recipeId()));*/
        //?}

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CraftQueueManager.tick(player);
                DeferredCraftSync.maybeSync(player); // no-op on 1.21.1
            }
        });
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
    // loader-version-agnostic so the shared source compiles on both MC lines. Payload registries
    // only exist from 1.20.5 (the CustomPacketPayload API); older lines use raw channel ids.
    //? if >=1.21.11 {
    /*private static PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s() {
        return PayloadTypeRegistry.serverboundPlay();
    }

    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c() {
        return PayloadTypeRegistry.clientboundPlay();
    }*/
    //?} else if >=1.20.5 {
    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s() {
        return PayloadTypeRegistry.playC2S();
    }

    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c() {
        return PayloadTypeRegistry.playS2C();
    }
    //?}
}
