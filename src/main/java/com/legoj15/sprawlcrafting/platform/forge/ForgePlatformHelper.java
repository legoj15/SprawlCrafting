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
    // Forge 47 (1.20.1) tracks presence per SimpleChannel, and most payloads share the one channel
    // (discriminators 0-2, frozen), so payloadId cannot narrow the answer there. Channel presence only
    // proves the peer has the original messages, so it must never vouch for a capability that is
    // told apart by its own id: ProgressStatesV2Payload (the marker for newer progress states) is not
    // registered on this line, and a 1.0.x client has the channel yet cannot decode those states. It
    // answers false, so the server always sends such a client the original state. CancelCraftPayload
    // is the one payload with its own channel on that line, so it answers for itself.
    private static boolean remoteHas(Connection connection, ResourceLocation payloadId) {
        //? if >=1.20.5 {
        return NetworkContext.get(connection).getRemoteChannels().contains(payloadId);
        //?} else {
        /*if (com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload.ID.equals(payloadId)) {
            return false;
        }
        if (com.legoj15.sprawlcrafting.network.CancelCraftPayload.ID.equals(payloadId)) {
            return ForgeNetwork.isCancelRemotePresent(connection);
        }
        return ForgeNetwork.isRemotePresent(connection);*/
        //?}
    }

    @Override
    public void sendToServer(SprawlPayload payload) {
        //? if >=1.20.5 {
        ForgeNetwork.channel().send(payload, PacketDistributor.SERVER.noArg());
        //?} else {
        /*// On Forge 47 the cancel payload rides its own channel (main's discriminators are frozen).
        if (payload instanceof com.legoj15.sprawlcrafting.network.CancelCraftPayload) {
            ForgeNetwork.cancelChannel().send(PacketDistributor.SERVER.noArg(), payload);
            return;
        }
        ForgeNetwork.channel().send(PacketDistributor.SERVER.noArg(), payload);*/
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
        // Forge 26.x made ModList a static facade (no get()).
        //? if >=1.21.11 {
        /*return ModList.isLoaded(modId);*/
        //?} else {
        return ModList.get().isLoaded(modId);
        //?}
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }
}
