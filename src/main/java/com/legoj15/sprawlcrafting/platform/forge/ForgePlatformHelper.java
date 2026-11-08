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
import net.minecraftforge.network.NetworkContext;
import net.minecraftforge.network.PacketDistributor;

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
        return remoteHas(player.connection.getConnection(), payloadId);
    }

    @Override
    public boolean canSendToServer(Connection connection, ResourceLocation payloadId) {
        return remoteHas(connection, payloadId);
    }

    // The remote's registered-channel list is what it sent in Forge's channel-registration payload; a
    // vanilla or modless peer never sends one, so it is empty and nothing is ever sent to it.
    private static boolean remoteHas(Connection connection, ResourceLocation payloadId) {
        return NetworkContext.get(connection).getRemoteChannels().contains(payloadId);
    }

    @Override
    public void sendToServer(SprawlPayload payload) {
        ForgeNetwork.channel().send(payload, PacketDistributor.SERVER.noArg());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SprawlPayload payload) {
        ForgeNetwork.channel().send(payload, player.connection.getConnection());
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
