package com.legoj15.sprawlcrafting.platform.forge;

import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.lang3.tuple.Pair;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.craft.StationStorages;
import com.legoj15.sprawlcrafting.network.CancelCraftPacket;
import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePacket;
import com.legoj15.sprawlcrafting.network.ProgressStatesV2;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPacket;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

/**
 * Forge 1.16.5 entrypoint: the optional network channel, the server tick loop and lifecycle cleanup.
 *
 * <p>Never gates server access on mod presence: the channel accepts {@link NetworkRegistry#ABSENT} and
 * {@link NetworkRegistry#ACCEPTVANILLA} on both sides, every S2C send is guarded by
 * {@code isRemotePresent}, and the DISPLAYTEST extension tells the server list to ignore us.
 */
@Mod(Constants.MOD_ID)
public class ForgeMod {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            Constants.id("main"),
            () -> PROTOCOL,
            ForgeMod::acceptsVersion,
            ForgeMod::acceptsVersion);

    /**
     * The progress-states-v2 capability marker ({@link ProgressStatesV2}): registered, never sent on. Forge
     * tracks presence per channel name from the login handshake, so its presence on the peer is the one
     * signal a mod-1.0.x peer (main channel only) cannot fake. Same lenient predicates as the main channel.
     */
    public static final SimpleChannel PROGRESS_STATES_V2 = NetworkRegistry.newSimpleChannel(
            ProgressStatesV2.ID,
            () -> PROTOCOL,
            ForgeMod::acceptsVersion,
            ForgeMod::acceptsVersion);

    /**
     * The player-initiated cancel (issue #31) rides its own channel instead of a fourth id on the main
     * one: the main channel's ids 0-2 are frozen, and an older peer cannot be handed id 3. Same lenient
     * predicates and protocol as the main channel, so both sides keep treating it as optional.
     */
    public static final SimpleChannel CANCEL_CRAFT = NetworkRegistry.newSimpleChannel(
            CancelCraftPacket.ID,
            () -> PROTOCOL,
            ForgeMod::acceptsVersion,
            ForgeMod::acceptsVersion);

    public ForgeMod() {
        // Server-list compatibility: present-or-absent on either side is fine.
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));

        registerMessages();
        // AE2 Crafting Terminal as a crafting station, only when AE2 is loaded (reflective, see the class).
        StationStorages.bootstrap();

        MinecraftForge.EVENT_BUS.addListener(ForgeMod::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ForgeMod::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ForgeMod::onServerStopping);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ForgeClient::init);
    }

    private static boolean acceptsVersion(String remote) {
        return PROTOCOL.equals(remote) || NetworkRegistry.ABSENT.equals(remote)
                || NetworkRegistry.ACCEPTVANILLA.equals(remote);
    }

    private static void registerMessages() {
        CHANNEL.registerMessage(0, StartDeferredCraftPacket.class,
                StartDeferredCraftPacket::write, StartDeferredCraftPacket::read,
                ForgeMod::handleStart, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, CraftingScreenStatePacket.class,
                CraftingScreenStatePacket::write, CraftingScreenStatePacket::read,
                ForgeMod::handleScreenState, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, CraftProgressPacket.class,
                CraftProgressPacket::write, CraftProgressPacket::read,
                ForgeMod::handleProgress, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CANCEL_CRAFT.registerMessage(0, CancelCraftPacket.class,
                CancelCraftPacket::write, CancelCraftPacket::read,
                ForgeMod::handleCancelCraft, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void handleStart(StartDeferredCraftPacket packet, Supplier<NetworkEvent.Context> ctx) {
        final ServerPlayerEntity player = ctx.get().getSender();
        if (player != null) {
            ctx.get().enqueueWork(() -> CraftRequests.handleStartRequest(player, packet.recipeId()));
        }
        ctx.get().setPacketHandled(true);
    }

    private static void handleScreenState(CraftingScreenStatePacket packet, Supplier<NetworkEvent.Context> ctx) {
        final ServerPlayerEntity player = ctx.get().getSender();
        if (player != null) {
            ctx.get().enqueueWork(() ->
                    ClientCraftingView.update(player.getUUID(), packet.gridWidth(), packet.gridHeight()));
        }
        ctx.get().setPacketHandled(true);
    }

    private static void handleProgress(CraftProgressPacket packet, Supplier<NetworkEvent.Context> ctx) {
        // Nested lambda keeps the client-only tracker class from loading on a dedicated server.
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientJobTracker.accept(packet)));
        ctx.get().setPacketHandled(true);
    }

    private static void handleCancelCraft(CancelCraftPacket packet, Supplier<NetworkEvent.Context> ctx) {
        final ServerPlayerEntity player = ctx.get().getSender();
        if (player != null) {
            ctx.get().enqueueWork(() -> CraftQueueManager.cancel(player));
        }
        ctx.get().setPacketHandled(true);
    }

    // End-of-server-tick player loop, mirroring the Fabric wiring so jobs tick identically on both
    // loaders (PlayerTickEvent skips players whose entity tick is suppressed; this does not).
    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            CraftQueueManager.tick(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CraftQueueManager.clear(event.getPlayer().getUUID());
        ClientCraftingView.clear(event.getPlayer().getUUID());
    }

    private static void onServerStopping(FMLServerStoppingEvent event) {
        CraftQueueManager.clearAll();
        ClientCraftingView.clearAll();
    }
}
