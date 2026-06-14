package com.legoj15.sprawlcrafting.network;

import java.util.List;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.craft.ItemDemand;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C (26.x only): the server-computed "gather list" for a recipe — the raw materials the player
 * still needs ({@code demands}), the recipe's result ({@code targetItem} + {@code targetCount}, for
 * the screen title and icon, since the 26.x client cannot resolve a recipe to its result locally),
 * and whether the search hit its budget so the list may be incomplete ({@code approximate}). An
 * empty {@code demands} means nothing to gather / not plannable.
 *
 * <p>The codec is declared over a bare {@link ByteBuf} (item identity travels as a
 * {@link ResourceLocation}, not an {@code ItemStack}), so it carries no registry and is
 * unit-testable; the client resolves the ids to items for icons.
 *
 * @param token        the request id this answers ({@link RequestShortfallByDisplayPayload} /
 *                     {@link RequestShortfallByRecipePayload}); the open screen matches on it
 * @param targetItem   the recipe result's item id
 * @param targetCount  how many the recipe yields
 * @param approximate  true if the demand list may undercount (search budget hit)
 * @param demands      the raw items + counts to acquire
 */
public record ShortfallPayload(int token, ResourceLocation targetItem, int targetCount,
                               boolean approximate, List<ItemDemand> demands) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShortfallPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "shortfall"));

    public static final StreamCodec<ByteBuf, ShortfallPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ShortfallPayload::token,
                    ResourceLocation.STREAM_CODEC, ShortfallPayload::targetItem,
                    ByteBufCodecs.VAR_INT, ShortfallPayload::targetCount,
                    ByteBufCodecs.BOOL, ShortfallPayload::approximate,
                    ItemDemand.STREAM_CODEC.apply(ByteBufCodecs.list()), ShortfallPayload::demands,
                    ShortfallPayload::new);

    @Override
    public CustomPacketPayload.Type<ShortfallPayload> type() {
        return TYPE;
    }
}
