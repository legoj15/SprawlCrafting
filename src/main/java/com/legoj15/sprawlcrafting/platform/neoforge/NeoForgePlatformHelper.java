package com.legoj15.sprawlcrafting.platform.neoforge;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.platform.services.IPlatformHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean canReceive(ServerPlayer player, ResourceLocation payloadId) {
        return NetworkRegistry.hasChannel(player.connection, payloadId);
    }

    @Override
    public boolean canSendToServer(Connection connection, ResourceLocation payloadId) {
        // null protocol = check the negotiated channels across all protocols (we're in PLAY).
        return NetworkRegistry.hasChannel(connection, null, payloadId);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        //? if >=1.21.11 {
        /*net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);*/
        //?} else {
        PacketDistributor.sendToServer(payload);
        //?}
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        //? if >=1.21.11 {
        /*return !net.neoforged.fml.loading.FMLEnvironment.isProduction();*/
        //?} else {
        return !FMLLoader.isProduction();
        //?}
    }
}