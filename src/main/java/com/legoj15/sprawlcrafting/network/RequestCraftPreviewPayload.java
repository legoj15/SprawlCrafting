package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S (26.x only): first click on a yellow recipe-book entry asks the server to plan it so the
 * intermediate-step breakdown can be shown in the button tooltip. The client cannot build the
 * plan itself on 26.x (no recipe graph), so the server replies with {@link CraftPreviewPayload}.
 *
 * @param displayId the {@code RecipeDisplayId} index of the clicked entry
 */
public record RequestCraftPreviewPayload(int displayId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestCraftPreviewPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_preview"));

    public static final StreamCodec<ByteBuf, RequestCraftPreviewPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RequestCraftPreviewPayload::displayId,
                    RequestCraftPreviewPayload::new);

    @Override
    public CustomPacketPayload.Type<RequestCraftPreviewPayload> type() {
        return TYPE;
    }
}
