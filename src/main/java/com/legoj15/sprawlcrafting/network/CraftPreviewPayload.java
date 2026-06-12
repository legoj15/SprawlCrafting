package com.legoj15.sprawlcrafting.network;

import java.util.List;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C (26.x only): the server-built plan preview for a clicked yellow recipe-book entry — the
 * same intermediate-step breakdown the 1.21.1 client computes locally, but planned server-side
 * and streamed down. The client shows {@code lines} in the entry's tooltip and arms the
 * second-click confirm. An empty list means "no longer plannable" (inventory changed): the
 * client drops the pending preview.
 *
 * @param displayId the entry these lines describe (matches the client's pending RecipeDisplayId)
 * @param lines     ready-to-render tooltip lines (header, per-intermediate totals, confirm hint)
 */
public record CraftPreviewPayload(int displayId, List<Component> lines) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CraftPreviewPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "craft_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftPreviewPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CraftPreviewPayload::displayId,
                    ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), CraftPreviewPayload::lines,
                    CraftPreviewPayload::new);

    @Override
    public CustomPacketPayload.Type<CraftPreviewPayload> type() {
        return TYPE;
    }
}
