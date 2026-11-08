package com.legoj15.sprawlcrafting.network;

import java.util.List;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.craft.ItemDemand;

import net.minecraft.resources.ResourceLocation;
//? if >=1.20.5 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * S2C (26.x only): the server-computed "gather list" for a recipe — the raw materials the player
 * still needs ({@code demands}), the recipe's result ({@code targetItem} + {@code targetCount}, for
 * the screen title and icon, since the 26.x client cannot resolve a recipe to its result locally),
 * and whether the search hit its budget so the list may be incomplete ({@code approximate}). An
 * empty {@code demands} means nothing to gather / not plannable.
 *
 * <p><b>Frozen mod-1.0.3 wire format.</b> Counts travel as VarInts (clamped to
 * {@code Integer.MAX_VALUE} on encode) and there is no saturated flag. The server sends this only to a
 * client that lacks {@link ShortfallV2Payload} (a 1.0.3 client); a new client still registers it so a
 * 1.0.3 server's replies keep working. Never change its layout: add a new payload id instead.
 *
 * <p>The codec is declared over a bare {@code ByteBuf} (item identity travels as a
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
                               boolean approximate, List<ItemDemand> demands) implements SprawlPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "shortfall");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<ShortfallPayload> TYPE = new CustomPacketPayload.Type<>(ID);

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
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeResourceLocation(targetItem);
        buf.writeVarInt(targetCount);
        buf.writeBoolean(approximate);
        buf.writeCollection(demands, (b, demand) -> demand.write(b));
    }

    public static ShortfallPayload read(FriendlyByteBuf buf) {
        return new ShortfallPayload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(),
                buf.readBoolean(), buf.readList(ItemDemand::read));
    }*/
    //?}
}
