package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S (26.x only): clicking the red JEI/REI transfer button asks the server for the recipe's
 * "gather list". The recipe viewer hands the transfer handler a {@code RecipeHolder}, so this
 * request carries the recipe identifier directly (unlike the recipe book, which only has a display
 * index — see {@link RequestShortfallByDisplayPayload}). The server resolves it via
 * {@code RecipeIds.byId} and replies with {@link ShortfallPayload}, echoing {@code token}.
 *
 * @param token    client-allocated correlation id, echoed back in {@link ShortfallPayload}
 * @param recipeId the recipe the viewer is showing
 */
public record RequestShortfallByRecipePayload(int token, ResourceLocation recipeId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestShortfallByRecipePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_shortfall_recipe"));

    public static final StreamCodec<ByteBuf, RequestShortfallByRecipePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RequestShortfallByRecipePayload::token,
                    ResourceLocation.STREAM_CODEC, RequestShortfallByRecipePayload::recipeId,
                    RequestShortfallByRecipePayload::new);

    @Override
    public CustomPacketPayload.Type<RequestShortfallByRecipePayload> type() {
        return TYPE;
    }
}
