package com.legoj15.sprawlcrafting.platform.fabric;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.network.CancelCraftPacket;
import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePacket;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPacket;
import com.legoj15.sprawlcrafting.network.StartProtectedCraftPacket;
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

    // The counts channel's own presence check, like canReceive for the main one: a mod-1.0.x client has
    // the main channel but would choke on the VarLong layout, so this channel is what says it decodes it.
    @Override
    public boolean canReceiveProgressCounts(ServerPlayerEntity player) {
        return ServerPlayNetworking.canSend(player, CraftProgressPacket.COUNTS_ID);
    }

    @Override
    public void sendProgressCounts(ServerPlayerEntity player, CraftProgressPacket packet) {
        if (!canReceiveProgressCounts(player)) {
            return;
        }
        PacketBuffer buf = PacketByteBufs.create();
        packet.writeCounts(buf);
        ServerPlayNetworking.send(player, CraftProgressPacket.COUNTS_ID, buf);
    }

    // No crafting-station provider exists on Fabric 1.16.5 (AE2 support is Forge-only), so no job here is
    // ever station-dependent and nothing on this loader asks either question with a station open.
    @Override
    public boolean canReceiveProgressStatesV2(ServerPlayerEntity player) {
        return false;
    }

    @Override
    public boolean serverSupportsStations() {
        return false;
    }

    // No crafting station exists on Fabric 1.16.5 (AE2 support is Forge-only), so a station protected
    // tier cannot exist here either (issue #49): nothing to count, nothing to negotiate.
    @Override
    public boolean serverSupportsStationProtected() {
        return false;
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

    // The protected start's own channel, like the cancel's: an older SprawlCrafting server accepts the
    // plain start channel but has no protected receiver, so this channel's presence is what says the
    // request would land (and the client never offers protected crafts without it).
    @Override
    public boolean canStartProtectedCraft() {
        return Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(StartProtectedCraftPacket.ID);
    }

    @Override
    public void sendStartProtectedCraft(ResourceLocation recipeId) {
        if (!canStartProtectedCraft()) {
            return;
        }
        PacketBuffer buf = PacketByteBufs.create();
        new StartProtectedCraftPacket(recipeId).write(buf);
        ClientPlayNetworking.send(StartProtectedCraftPacket.ID, buf);
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

    // The cancel's own channel, not serverHasMod(): an older SprawlCrafting server accepts the start
    // channel but has no cancel receiver, so its presence here is what says the request would land.
    @Override
    public boolean canCancelCraft() {
        return Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(CancelCraftPacket.ID);
    }

    @Override
    public void sendCancelCraft() {
        if (!canCancelCraft()) {
            return;
        }
        PacketBuffer buf = PacketByteBufs.create();
        new CancelCraftPacket().write(buf); // writes nothing: an empty buffer is the whole payload
        ClientPlayNetworking.send(CancelCraftPacket.ID, buf);
    }
}
