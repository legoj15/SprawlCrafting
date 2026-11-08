package com.legoj15.sprawlcrafting.platform.fabric;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.network.SprawlPayload;
import com.legoj15.sprawlcrafting.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
//?} else {
/*import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;*/
//?}

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean canReceive(ServerPlayer player, ResourceLocation payloadId) {
        return ServerPlayNetworking.canSend(player, payloadId);
    }

    @Override
    public boolean canSendToServer(Connection connection, ResourceLocation payloadId) {
        // Fabric resolves the live client connection itself; the connection arg is unused here.
        return ClientPlayNetworking.canSend(payloadId);
    }

    @Override
    public void sendToServer(SprawlPayload payload) {
        // Fabric's own C2S send path; the raw vanilla ServerboundCustomPayloadPacket does
        // not reach Fabric's server-side receiver. Client-only (ClientPlayNetworking loads
        // lazily, never on a dedicated server since this is only called client-side).
        //? if >=1.20.5 {
        ClientPlayNetworking.send(payload);
        //?} else {
        /*// Pre-1.20.5 Fabric networking is channel id + raw buffer.
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ClientPlayNetworking.send(payload.id(), buf);*/
        //?}
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SprawlPayload payload) {
        //? if >=1.20.5 {
        // Vanilla send path — identical bytes to ServerPlayNetworking.send for a registered payload.
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
        //?} else {
        /*FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ServerPlayNetworking.send(player, payload.id(), buf);*/
        //?}
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
