package com.legoj15.sprawlcrafting.platform.forge;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.network.CancelCraftPacket;
import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePacket;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPacket;
import com.legoj15.sprawlcrafting.platform.IPlatformHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.network.PacketDistributor;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public boolean canReceive(ServerPlayerEntity player) {
        return player.connection != null && ForgeMod.CHANNEL.isRemotePresent(player.connection.getConnection());
    }

    @Override
    public void sendProgress(ServerPlayerEntity player, CraftProgressPacket packet) {
        if (canReceive(player)) {
            ForgeMod.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    @Override
    public boolean canReceiveProgressStatesV2(ServerPlayerEntity player) {
        return player.connection != null
                && ForgeMod.PROGRESS_STATES_V2.isRemotePresent(player.connection.getConnection());
    }

    @Override
    public boolean serverSupportsStations() {
        ClientPlayNetHandler connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeMod.PROGRESS_STATES_V2.isRemotePresent(connection.getConnection());
    }

    @Override
    public double blockInteractionDistanceSqr(ServerPlayerEntity player) {
        // Mirrors ServerPlayNetHandler#handleUseItemOn: (REACH_DISTANCE + 3)^2.
        ModifiableAttributeInstance reach =
                player.getAttribute(net.minecraftforge.common.ForgeMod.REACH_DISTANCE.get());
        double dist = (reach != null ? reach.getValue() : 5.0D) + 3.0D;
        return dist * dist;
    }

    @Override
    public boolean serverHasMod() {
        ClientPlayNetHandler connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeMod.CHANNEL.isRemotePresent(connection.getConnection());
    }

    @Override
    public void sendStartCraft(ResourceLocation recipeId) {
        if (serverHasMod()) {
            ForgeMod.CHANNEL.sendToServer(new StartDeferredCraftPacket(recipeId));
        }
    }

    @Override
    public void sendScreenState(int gridWidth, int gridHeight) {
        if (serverHasMod()) {
            ForgeMod.CHANNEL.sendToServer(new CraftingScreenStatePacket(gridWidth, gridHeight));
        }
    }

    // The cancel's own channel, not serverHasMod(): a mod-1.0.x server has the main channel but no
    // cancel handler, so its presence here is what says the request would reach CraftQueueManager.cancel.
    @Override
    public boolean canCancelCraft() {
        ClientPlayNetHandler connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeMod.CANCEL_CRAFT.isRemotePresent(connection.getConnection());
    }

    @Override
    public void sendCancelCraft() {
        if (canCancelCraft()) {
            ForgeMod.CANCEL_CRAFT.sendToServer(new CancelCraftPacket());
        }
    }
}
