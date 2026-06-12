package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S (26.x only): the player confirmed a yellow recipe-book entry. On 26.x the client only
 * holds an opaque {@code RecipeDisplayId} for the entry (no recipe identifier), so the start
 * request carries that display index; the server maps it back to the recipe via
 * {@code RecipeManager.getRecipeFromDisplay} before re-planning authoritatively. (On 1.21.1 the
 * client has the recipe and uses {@link StartDeferredCraftPayload} directly; the JEI path uses
 * that identifier payload on both versions since JEI supplies the recipe.)
 *
 * @param displayId the {@code RecipeDisplayId} index the recipe book was showing
 */
public record StartDeferredCraftByDisplayPayload(int displayId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StartDeferredCraftByDisplayPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_craft_display"));

    public static final StreamCodec<ByteBuf, StartDeferredCraftByDisplayPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StartDeferredCraftByDisplayPayload::displayId,
                    StartDeferredCraftByDisplayPayload::new);

    @Override
    public CustomPacketPayload.Type<StartDeferredCraftByDisplayPayload> type() {
        return TYPE;
    }
}
