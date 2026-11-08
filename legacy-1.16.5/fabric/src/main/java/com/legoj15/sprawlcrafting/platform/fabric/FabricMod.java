package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePacket;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPacket;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.ServerPlayerEntity;

/**
 * Fabric 1.16.5 entrypoint. Fabric's play channels are optional by construction (no handshake gate);
 * S2C sends are guarded by {@code ServerPlayNetworking.canSend} in {@link FabricPlatformHelper}.
 *
 * <p>1.16.5's networking API runs receivers on the netty thread: the packet is decoded there (the
 * buffer is released afterwards) and the work is handed to the server thread.
 */
public class FabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(StartDeferredCraftPacket.ID,
                (server, player, handler, buf, sender) -> {
                    final StartDeferredCraftPacket packet = StartDeferredCraftPacket.read(buf);
                    server.execute(() -> CraftRequests.handleStartRequest(player, packet.recipeId()));
                });
        ServerPlayNetworking.registerGlobalReceiver(CraftingScreenStatePacket.ID,
                (server, player, handler, buf, sender) -> {
                    final CraftingScreenStatePacket packet = CraftingScreenStatePacket.read(buf);
                    server.execute(() -> ClientCraftingView.update(
                            player.getUUID(), packet.gridWidth(), packet.gridHeight()));
                });

        // End-of-server-tick player loop, mirroring the Forge wiring so jobs tick identically.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
                CraftQueueManager.tick(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CraftQueueManager.clear(handler.player.getUUID());
            ClientCraftingView.clear(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CraftQueueManager.clearAll();
            ClientCraftingView.clearAll();
        });
    }
}
