package com.legoj15.sprawlcrafting.platform.forge;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.network.SprawlPayload;
import com.legoj15.sprawlcrafting.platform.services.IPlatformHelper;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;
//? if >=1.20.5 {
import net.minecraftforge.network.NetworkContext;
//?}

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean canReceive(ServerPlayer player, ResourceLocation payloadId) {
        //? if >=1.20.5 {
        return remoteHas(player.connection.getConnection(), payloadId);
        //?} else {
        /*return remoteHas(player.connection.connection, payloadId);*/
        //?}
    }

    @Override
    public boolean canSendToServer(Connection connection, ResourceLocation payloadId) {
        return remoteHas(connection, payloadId);
    }

    // The remote's registered-channel list is what it sent in Forge's channel-registration payload; a
    // vanilla or modless peer never sends one, so it is empty and nothing is ever sent to it.
    // Forge 47 (1.20.1) tracks presence per SimpleChannel, and all three payloads share the one channel,
    // so payloadId cannot narrow the answer there.
    private static boolean remoteHas(Connection connection, ResourceLocation payloadId) {
        //? if >=1.20.5 {
        return NetworkContext.get(connection).getRemoteChannels().contains(payloadId);
        //?} else {
        /*return ForgeNetwork.isRemotePresent(connection);*/
        //?}
    }

    @Override
    public void sendToServer(SprawlPayload payload) {
        //? if >=1.20.5 {
        ForgeNetwork.channel().send(payload, PacketDistributor.SERVER.noArg());
        //?} else {
        /*ForgeNetwork.channel().send(PacketDistributor.SERVER.noArg(), payload);*/
        //?}
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SprawlPayload payload) {
        //? if >=1.20.5 {
        ForgeNetwork.channel().send(payload, player.connection.getConnection());
        //?} else {
        /*ForgeNetwork.channel().send(PacketDistributor.PLAYER.with(() -> player), payload);*/
        //?}
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}
