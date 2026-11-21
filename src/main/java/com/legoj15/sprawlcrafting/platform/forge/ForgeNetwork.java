package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.network.CancelCraftPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressCountsPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;
import com.legoj15.sprawlcrafting.network.StartProtectedCraftPayload;
//? if >=1.21.11 {
/*import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.craft.DeferredCraftSync;
import com.legoj15.sprawlcrafting.network.CraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.RequestCraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.RequestShortfallByDisplayPayload;
import com.legoj15.sprawlcrafting.network.RequestShortfallByRecipePayload;
import com.legoj15.sprawlcrafting.network.ShortfallPayload;
import com.legoj15.sprawlcrafting.network.ShortfallV2Payload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftByDisplayPayload;
import com.legoj15.sprawlcrafting.network.StartProtectedCraftByDisplayPayload;*/
//?}

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.20.5 {
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
//?} else {
/*import net.minecraft.network.Connection;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;*/
//?}

/**
 * The mod's one Forge payload channel. Forge 1.21.1 cannot encode a bare {@code CustomPacketPayload} in
 * a vanilla packet (its patched codec only understands its own wrapper), so every payload is registered
 * here and sent through {@link Channel#send}; {@link ForgePlatformHelper} is the only caller.
 *
 * <p>Network-optional by construction (the mod must never gate server access on its own presence):
 * {@code optional()} lets a vanilla/modless peer connect, and the version test accepts every protocol
 * version so a client/server pair on different mod builds can still connect (a payload id the other
 * side lacks is simply never "present", so the sender skips it via {@code canReceive}). The Forge
 * payload channel registers each payload's type id as a child channel, so the ids on the wire are the
 * same {@code sprawlcrafting:*} ids Fabric and NeoForge use.
 *
 * <p>Forge 47 (MC 1.20.1) predates payload channels: there the same three payloads ride one
 * {@code SimpleChannel} ({@code sprawlcrafting:main}, discriminators 0-2, frozen: a peer on an older
 * jar fails on any id it lacks, so nothing may be appended) whose version predicates accept
 * everything, including {@code ABSENT} (peer lacks the channel) and {@code ACCEPTVANILLA} (vanilla peer), so it is optional both ways. Presence is per channel on that
 * line, not per payload ({@link #isRemotePresent}). {@code CancelCraftPayload} arrived after that
 * freeze, so on this line it rides its own {@code sprawlcrafting:cancel_craft} channel (discriminator 0)
 * built with the same predicates, and answers presence through {@link #isCancelRemotePresent}. So do
 * {@code StartProtectedCraftPayload} (#40) and {@code CraftProgressCountsPayload} (#46).
 */
final class ForgeNetwork {

    //? if >=1.20.5 {
    private static Channel<CustomPacketPayload> channel;

    private ForgeNetwork() {
    }

    /** Builds the channel. Must run during mod construction: Forge locks the registry after that. */
    static void register() {
        // 1.21.1 registers three payloads; the 26.x server-driven recipe-book payloads join the chain
        // behind `//? if >=1.21.11` directives (mirroring NeoForgeMod.onRegisterPayloads).
        channel = ChannelBuilder.named(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "main"))
                .acceptedVersions((status, version) -> true)
                .optional()
                .payloadChannel()
                .play()
                .serverbound()
                // addMain: the handler runs on the server thread, like NeoForge/Fabric handlers do.
                .addMain(StartDeferredCraftPayload.TYPE, widen(StartDeferredCraftPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                CraftRequests.handleStartRequest(sender, payload.recipeId());
                            }
                        })
                .addMain(CraftingScreenStatePayload.TYPE, widen(CraftingScreenStatePayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                ClientCraftingView.update(sender.getUUID(), payload.gridWidth(), payload.gridHeight());
                            }
                        })
                .addMain(CancelCraftPayload.TYPE, widen(CancelCraftPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                CraftQueueManager.cancel(sender);
                            }
                        })
                // The protected start (#40): its own payload id on this per-payload channel, so an
                // older client that lacks it is told apart by id (canSendToServer/canReceive).
                .addMain(StartProtectedCraftPayload.TYPE, widen(StartProtectedCraftPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                CraftRequests.handleStartRequest(sender, payload.recipeId(), true);
                            }
                        })
                //? if >=1.21.11 {
                /*.addMain(StartDeferredCraftByDisplayPayload.TYPE, widen(StartDeferredCraftByDisplayPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                DeferredCraftSync.handleStartByDisplay(sender, payload.displayId());
                            }
                        })
                // The protected by-display start (#40), the recipe book's confirm for a protected-only entry.
                .addMain(StartProtectedCraftByDisplayPayload.TYPE, widen(StartProtectedCraftByDisplayPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                DeferredCraftSync.handleStartByDisplay(sender, payload.displayId(), true);
                            }
                        })
                .addMain(RequestCraftPreviewPayload.TYPE, widen(RequestCraftPreviewPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                DeferredCraftSync.handlePreviewRequest(sender, payload.displayId());
                            }
                        })
                .addMain(RequestShortfallByDisplayPayload.TYPE, widen(RequestShortfallByDisplayPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                DeferredCraftSync.handleShortfallByDisplay(sender, payload.token(), payload.displayId());
                            }
                        })
                .addMain(RequestShortfallByRecipePayload.TYPE, widen(RequestShortfallByRecipePayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                DeferredCraftSync.handleShortfallByRecipe(sender, payload.token(), payload.recipeId());
                            }
                        })*/
                //?}
                .clientbound()
                // Lambda body, not a method reference: keeps the client-only tracker class from
                // loading on the dedicated server.
                .addMain(CraftProgressPayload.TYPE, widen(CraftProgressPayload.STREAM_CODEC),
                        (payload, context) -> ClientJobTracker.accept(payload))
                // Capability marker only (never sent): tells the server this client decodes PAUSED_STATION.
                .addMain(com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload.TYPE,
                        widen(com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload.STREAM_CODEC),
                        (payload, context) -> { })
                // The long-count snapshot (#46): its own payload id, so canReceive tells a client that
                // takes real longs apart from one on the scaled-int legacy payload.
                .addMain(CraftProgressCountsPayload.TYPE, widen(CraftProgressCountsPayload.STREAM_CODEC),
                        (payload, context) -> ClientJobTracker.accept(payload.toProgressPayload()))
                //? if >=1.21.11 {
                /*.addMain(DeferredCraftableSyncPayload.TYPE, widen(DeferredCraftableSyncPayload.STREAM_CODEC),
                        (payload, context) -> DeferredCraftableCache.accept(payload.displayIds(), payload.recipeIds()))
                // The protected-only subset of that set (#40): pushed right after it, only to clients
                // that registered this channel (the server checks canReceive before sending).
                .addMain(ProtectedCraftableSyncPayload.TYPE, widen(ProtectedCraftableSyncPayload.STREAM_CODEC),
                        (payload, context) -> DeferredCraftableCache.acceptProtected(
                                payload.displayIds(), payload.recipeIds()))
                .addMain(CraftPreviewPayload.TYPE, widen(CraftPreviewPayload.STREAM_CODEC),
                        (payload, context) -> DeferredClickState.acceptPreview(payload.displayId(), payload.lines()))
                .addMain(ShortfallPayload.TYPE, widen(ShortfallPayload.STREAM_CODEC),
                        (payload, context) -> MissingIngredients.accept(
                                payload.token(), payload.targetItem(), payload.targetCount(),
                                payload.approximate(), false, payload.demands()))
                // V2 (long counts + saturated); the legacy receiver above stays for servers that only send it.
                .addMain(ShortfallV2Payload.TYPE, widen(ShortfallV2Payload.STREAM_CODEC),
                        (payload, context) -> MissingIngredients.accept(
                                payload.token(), payload.targetItem(), payload.targetCount(),
                                payload.approximate(), payload.saturated(), payload.demands()))*/
                //?}
                .build();
    }

    static Channel<CustomPacketPayload> channel() {
        return channel;
    }

    /**
     * Forge's flow builder wants a codec over exactly {@link RegistryFriendlyByteBuf}, but most of our
     * payloads declare theirs over a bare {@code ByteBuf} (they carry no registry data). A codec only
     * ever consumes its buffer, so a supertype codec is safe to hand a registry buffer.
     */
    private static <T extends CustomPacketPayload> StreamCodec<RegistryFriendlyByteBuf, T> widen(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return StreamCodec.of(codec::encode, codec::decode);
    }
    //?} else {
    /*// Any value works: both predicates below accept every version string.
    private static final String PROTOCOL = "1";

    private static SimpleChannel channel;

    private static SimpleChannel cancelChannel;

    private static SimpleChannel protectedChannel;

    private static SimpleChannel countsChannel;

    private ForgeNetwork() {
    }

    // Builds the channels. Must run during mod construction: Forge locks the registry after that.
    static void register() {
        channel = NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "main"))
                .networkProtocolVersion(() -> PROTOCOL)
                // Accept-all, so NetworkRegistry.ABSENT / ACCEPTVANILLA pass too: optional both ways.
                .clientAcceptedVersions(version -> true)
                .serverAcceptedVersions(version -> true)
                .simpleChannel();
        // Discriminators are the wire ids on this channel: never reorder, only append.
        // consumerMainThread: the handler runs on the server/client thread, like the other loaders.
        channel.messageBuilder(StartDeferredCraftPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StartDeferredCraftPayload::write)
                .decoder(StartDeferredCraftPayload::read)
                .consumerMainThread((payload, context) -> {
                    ServerPlayer sender = context.get().getSender();
                    if (sender != null) {
                        CraftRequests.handleStartRequest(sender, payload.recipeId());
                    }
                })
                .add();
        channel.messageBuilder(CraftingScreenStatePayload.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CraftingScreenStatePayload::write)
                .decoder(CraftingScreenStatePayload::read)
                .consumerMainThread((payload, context) -> {
                    ServerPlayer sender = context.get().getSender();
                    if (sender != null) {
                        ClientCraftingView.update(sender.getUUID(), payload.gridWidth(), payload.gridHeight());
                    }
                })
                .add();
        // Lambda body, not a method reference: keeps the client-only tracker class from loading on the
        // dedicated server.
        channel.messageBuilder(CraftProgressPayload.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CraftProgressPayload::write)
                .decoder(CraftProgressPayload::read)
                .consumerMainThread((payload, context) -> ClientJobTracker.accept(payload))
                .add();
        // The cancel payload cannot append to main (its discriminators 0-2 are frozen: a peer on an
        // older jar would fail on an unknown discriminator 3), so it rides its own channel with a
        // fresh discriminator space — same accept-all predicates, so optional both ways too.
        cancelChannel = NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cancel_craft"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(version -> true)
                .serverAcceptedVersions(version -> true)
                .simpleChannel();
        cancelChannel.messageBuilder(CancelCraftPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CancelCraftPayload::write)
                .decoder(CancelCraftPayload::read)
                .consumerMainThread((payload, context) -> {
                    ServerPlayer sender = context.get().getSender();
                    if (sender != null) {
                        CraftQueueManager.cancel(sender);
                    }
                })
                .add();
        // The protected start (#40) rides its own channel for the same reason as the cancel: main's
        // discriminators are frozen, and this payload's presence on the peer is what tells the client
        // a protected request would be honoured (an older server never negotiates it).
        protectedChannel = NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_craft_protected"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(version -> true)
                .serverAcceptedVersions(version -> true)
                .simpleChannel();
        protectedChannel.messageBuilder(StartProtectedCraftPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(StartProtectedCraftPayload::write)
                .decoder(StartProtectedCraftPayload::read)
                .consumerMainThread((payload, context) -> {
                    ServerPlayer sender = context.get().getSender();
                    if (sender != null) {
                        CraftRequests.handleStartRequest(sender, payload.recipeId(), true);
                    }
                })
                .add();
        // The long-count progress snapshot (#46) rides its own channel for the same reason as the
        // cancel and the protected start: main's discriminators are frozen, and this channel's
        // presence on the peer is what tells the server the client takes real long counts (an older
        // client keeps getting the scaled-int legacy payload on main).
        countsChannel = NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "craft_progress_counts"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(version -> true)
                .serverAcceptedVersions(version -> true)
                .simpleChannel();
        countsChannel.messageBuilder(CraftProgressCountsPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CraftProgressCountsPayload::write)
                .decoder(CraftProgressCountsPayload::read)
                // Lambda body, not a method reference: keeps the client-only tracker class from loading
                // on the dedicated server.
                .consumerMainThread((payload, context) -> ClientJobTracker.accept(payload.toProgressPayload()))
                .add();
    }

    static SimpleChannel channel() {
        return channel;
    }

    static SimpleChannel cancelChannel() {
        return cancelChannel;
    }

    static SimpleChannel protectedChannel() {
        return protectedChannel;
    }

    static SimpleChannel countsChannel() {
        return countsChannel;
    }

    // Whether the peer on this connection registered the channel. A vanilla or modless peer never does,
    // so nothing is ever sent to it.
    static boolean isRemotePresent(Connection connection) {
        return channel.isRemotePresent(connection);
    }

    // Same, for the cancel payload's own channel: a server on an older jar (before the cancel key)
    // never negotiates it, so the client's key stays inert against it.
    static boolean isCancelRemotePresent(Connection connection) {
        return cancelChannel.isRemotePresent(connection);
    }

    // Same, for the protected start's own channel (#40): a server on an older jar (before the
    // protected tier) never negotiates it, so the client never offers protected crafts against it.
    static boolean isProtectedRemotePresent(Connection connection) {
        return protectedChannel.isRemotePresent(connection);
    }

    // Same, for the counts payload's own channel (#46): a client on an older jar (before the long
    // counts) never negotiates it, so the server keeps sending it the scaled-int legacy payload.
    static boolean isCountsRemotePresent(Connection connection) {
        return countsChannel.isRemotePresent(connection);
    }*/
    //?}
}
