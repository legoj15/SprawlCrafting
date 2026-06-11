package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean canReceive(ServerPlayer player, ResourceLocation payloadId) {
        return ServerPlayNetworking.canSend(player, payloadId);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // Fabric's own C2S send path; the raw vanilla ServerboundCustomPayloadPacket does
        // not reach Fabric's server-side receiver. Client-only (ClientPlayNetworking loads
        // lazily, never on a dedicated server since this is only called client-side).
        ClientPlayNetworking.send(payload);
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
