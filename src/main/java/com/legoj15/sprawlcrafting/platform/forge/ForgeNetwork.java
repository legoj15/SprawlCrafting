package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;
//? if >=1.21.11 {
/*import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.craft.DeferredCraftSync;
import com.legoj15.sprawlcrafting.network.CraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.RequestCraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.RequestShortfallByDisplayPayload;
import com.legoj15.sprawlcrafting.network.RequestShortfallByRecipePayload;
import com.legoj15.sprawlcrafting.network.ShortfallPayload;
import com.legoj15.sprawlcrafting.network.ShortfallV2Payload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftByDisplayPayload;*/
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
 * {@code SimpleChannel} ({@code sprawlcrafting:main}, discriminators 0-2, frozen — append new messages
 * only) whose version predicates accept everything, including {@code ABSENT} (peer lacks the channel)
 * and {@code ACCEPTVANILLA} (vanilla peer), so it is optional both ways. Presence is per channel on that
 * line, not per payload ({@link #isRemotePresent}).
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
                //? if >=1.21.11 {
                /*.addMain(StartDeferredCraftByDisplayPayload.TYPE, widen(StartDeferredCraftByDisplayPayload.STREAM_CODEC),
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                DeferredCraftSync.handleStartByDisplay(sender, payload.displayId());
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
                //? if >=1.21.11 {
                /*.addMain(DeferredCraftableSyncPayload.TYPE, widen(DeferredCraftableSyncPayload.STREAM_CODEC),
                        (payload, context) -> DeferredCraftableCache.accept(payload.displayIds(), payload.recipeIds()))
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

    private ForgeNetwork() {
    }

    // Builds the channel. Must run during mod construction: Forge locks the registry after that.
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
    }

    static SimpleChannel channel() {
        return channel;
    }

    // Whether the peer on this connection registered the channel. A vanilla or modless peer never does,
    // so nothing is ever sent to it.
    static boolean isRemotePresent(Connection connection) {
        return channel.isRemotePresent(connection);
    }*/
    //?}
}
