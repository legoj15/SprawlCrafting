package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.craft.ClientCraftingView;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.CraftingScreenStatePayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;

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
 */
final class ForgeNetwork {

    private static Channel<CustomPacketPayload> channel;

    private ForgeNetwork() {
    }

    /** Builds the channel. Must run during mod construction: Forge locks the registry after that. */
    static void register() {
        // 1.21.1 registers three payloads; the 26.x-only payloads join this chain behind a
        // `//? if >=1.21.11` directive when a Forge 26.x node exists.
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
                .clientbound()
                // Lambda body, not a method reference: keeps the client-only tracker class from
                // loading on the dedicated server.
                .addMain(CraftProgressPayload.TYPE, widen(CraftProgressPayload.STREAM_CODEC),
                        (payload, context) -> ClientJobTracker.accept(payload))
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
}
