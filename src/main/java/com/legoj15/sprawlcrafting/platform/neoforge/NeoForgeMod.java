package com.legoj15.sprawlcrafting.platform.neoforge;

import com.legoj15.sprawlcrafting.CommonClass;
import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
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

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class NeoForgeMod {

    public NeoForgeMod(IEventBus eventBus) {
        CommonClass.init();

        // Payload registration is a mod-bus event; gameplay hooks live on the game bus.
        eventBus.addListener(NeoForgeMod::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(NeoForgeMod::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NeoForgeMod::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(NeoForgeMod::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(NeoForgeMod::onServerStopping);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // optional(): a modless/vanilla client is never kicked for lacking these channels;
        // the server guards each send with a per-player channel check instead.
        var registrar = event.registrar("1").optional();
        registrar.playToServer(
                StartDeferredCraftPayload.TYPE,
                StartDeferredCraftPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        CraftRequests.handleStartRequest(serverPlayer, payload.recipeId());
                    }
                });
        registrar.playToClient(
                CraftProgressPayload.TYPE,
                CraftProgressPayload.STREAM_CODEC,
                // Lambda body, not a method reference: keeps the client-only
                // tracker class from loading on the dedicated server.
                (payload, context) -> ClientJobTracker.accept(payload));

        // 26.x server-driven recipe-book layer (1.21.1 computes it client-side, no payloads).
        //? if >=1.21.11 {
        /*registrar.playToServer(
                StartDeferredCraftByDisplayPayload.TYPE,
                StartDeferredCraftByDisplayPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        DeferredCraftSync.handleStartByDisplay(serverPlayer, payload.displayId());
                    }
                });
        registrar.playToServer(
                RequestCraftPreviewPayload.TYPE,
                RequestCraftPreviewPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        DeferredCraftSync.handlePreviewRequest(serverPlayer, payload.displayId());
                    }
                });
        registrar.playToClient(
                DeferredCraftableSyncPayload.TYPE,
                DeferredCraftableSyncPayload.STREAM_CODEC,
                (payload, context) -> DeferredCraftableCache.accept(payload.displayIds(), payload.recipeIds()));
        registrar.playToClient(
                CraftPreviewPayload.TYPE,
                CraftPreviewPayload.STREAM_CODEC,
                (payload, context) -> DeferredClickState.acceptPreview(payload.displayId(), payload.lines()));*/
        //?}
    }

    // End-of-server-tick player loop, deliberately mirroring the Fabric wiring so jobs
    // tick identically on both loaders (PlayerTickEvent skips players whose entity tick
    // is suppressed, e.g. spectators in unloaded chunks — this does not).
    private static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CraftQueueManager.tick(player);
            DeferredCraftSync.maybeSync(player); // no-op on 1.21.1
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        SprawlCraftingCommand.register(event.getDispatcher());
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CraftQueueManager.clear(event.getEntity().getUUID());
        DeferredCraftSync.clear(event.getEntity().getUUID()); // no-op on 1.21.1
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CraftQueueManager.clearAll();
        DeferredCraftSync.clearAll(); // no-op on 1.21.1; prevents a cross-world debounce leak on 26.x
    }
}
