package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S (26.x only): right-clicking a red recipe-book entry asks the server for its "gather list" —
 * the raw materials still needed to make it. The 26.x client holds only an opaque
 * {@code RecipeDisplayId}, so the request carries that index; the server maps it back via
 * {@code RecipeManager.getRecipeFromDisplay} and replies with {@link ShortfallPayload}. The
 * client-generated {@code token} is echoed in the reply so the open screen accepts only its own
 * answer (and ignores a stale one from a previous query). 1.21.1 computes the list locally.
 *
 * @param token     client-allocated correlation id, echoed back in {@link ShortfallPayload}
 * @param displayId the {@code RecipeDisplayId} index of the clicked entry
 */
public record RequestShortfallByDisplayPayload(int token, int displayId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestShortfallByDisplayPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_shortfall_display"));

    public static final StreamCodec<ByteBuf, RequestShortfallByDisplayPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RequestShortfallByDisplayPayload::token,
                    ByteBufCodecs.VAR_INT, RequestShortfallByDisplayPayload::displayId,
                    RequestShortfallByDisplayPayload::new);

    @Override
    public CustomPacketPayload.Type<RequestShortfallByDisplayPayload> type() {
        return TYPE;
    }
}
