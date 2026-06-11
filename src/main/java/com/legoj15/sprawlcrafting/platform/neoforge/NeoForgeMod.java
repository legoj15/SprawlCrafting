package com.legoj15.sprawlcrafting.platform.neoforge;

import com.legoj15.sprawlcrafting.CommonClass;
import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.command.SprawlCraftingCommand;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
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
        event.registrar("1").optional()
                .playToServer(
                        StartDeferredCraftPayload.TYPE,
                        StartDeferredCraftPayload.STREAM_CODEC,
                        (payload, context) -> {
                            if (context.player() instanceof ServerPlayer serverPlayer) {
                                CraftRequests.handleStartRequest(serverPlayer, payload.recipeId());
                            }
                        })
                .playToClient(
                        CraftProgressPayload.TYPE,
                        CraftProgressPayload.STREAM_CODEC,
                        // Lambda body, not a method reference: keeps the client-only
                        // tracker class from loading on the dedicated server.
                        (payload, context) -> ClientJobTracker.accept(payload));
    }

    // End-of-server-tick player loop, deliberately mirroring the Fabric wiring so jobs
    // tick identically on both loaders (PlayerTickEvent skips players whose entity tick
    // is suppressed, e.g. spectators in unloaded chunks — this does not).
    private static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CraftQueueManager.tick(player);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        SprawlCraftingCommand.register(event.getDispatcher());
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CraftQueueManager.clear(event.getEntity().getUUID());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CraftQueueManager.clearAll();
    }
}
