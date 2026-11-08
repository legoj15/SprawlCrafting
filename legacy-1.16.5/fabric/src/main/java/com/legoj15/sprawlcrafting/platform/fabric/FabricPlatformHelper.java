package com.legoj15.sprawlcrafting.platform.fabric;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePacket;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPacket;
import com.legoj15.sprawlcrafting.platform.IPlatformHelper;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

public class FabricPlatformHelper implements IPlatformHelper {

    /** Vanilla 1.16.5's fixed use-item-on distance check (ServerPlayNetHandler#handleUseItemOn). */
    private static final double VANILLA_INTERACTION_DISTANCE_SQR = 64.0D;

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean canReceive(ServerPlayerEntity player) {
        return ServerPlayNetworking.canSend(player, CraftProgressPacket.ID);
    }

    @Override
    public void sendProgress(ServerPlayerEntity player, CraftProgressPacket packet) {
        if (!canReceive(player)) {
            return;
        }
        PacketBuffer buf = PacketByteBufs.create();
        packet.write(buf);
        ServerPlayNetworking.send(player, CraftProgressPacket.ID, buf);
    }

    @Override
    public double blockInteractionDistanceSqr(ServerPlayerEntity player) {
        return VANILLA_INTERACTION_DISTANCE_SQR;
    }

    @Override
    public boolean serverHasMod() {
        return Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(StartDeferredCraftPacket.ID);
    }

    @Override
    public void sendStartCraft(ResourceLocation recipeId) {
        if (!serverHasMod()) {
            return;
        }
        PacketBuffer buf = PacketByteBufs.create();
        new StartDeferredCraftPacket(recipeId).write(buf);
        ClientPlayNetworking.send(StartDeferredCraftPacket.ID, buf);
    }

    @Override
    public void sendScreenState(int gridWidth, int gridHeight) {
        if (Minecraft.getInstance().getConnection() == null
                || !ClientPlayNetworking.canSend(CraftingScreenStatePacket.ID)) {
            return;
        }
        PacketBuffer buf = PacketByteBufs.create();
        new CraftingScreenStatePacket(gridWidth, gridHeight).write(buf);
        ClientPlayNetworking.send(CraftingScreenStatePacket.ID, buf);
    }
}
