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
 * S2C (26.x only): the gather list with full {@code long} counts — the successor of
 * {@link ShortfallPayload}, on its own channel id ({@code sprawlcrafting:shortfall_v2}) so a mod-1.0.3
 * peer never sees bytes it would mis-parse. Same fields plus {@code saturated} (some count hit
 * {@link Long#MAX_VALUE}; the true figure is at least that), and each {@link ItemDemand} count is a
 * VarLong ({@link ItemDemand#LONG_STREAM_CODEC}).
 *
 * <p>Version negotiation is the channel list itself: the server sends this when
 * {@code canReceive(player, ShortfallV2Payload.ID)} (the client registered it, i.e. it is a new
 * client), else falls back to {@link ShortfallPayload} with clamped ints, else sends nothing. A new
 * client keeps a receiver for both, so it also works against a 1.0.3 server. Registered optional like
 * every other payload, so its absence on either side never gates a connection.
 *
 * @param token        the request id this answers; the open screen matches on it
 * @param targetItem   the recipe result's item id
 * @param targetCount  how many the recipe yields
 * @param approximate  true if the demand list may undercount (search budget hit)
 * @param saturated    true if any count saturated at {@link Long#MAX_VALUE}
 * @param demands      the raw items + counts to acquire
 */
public record ShortfallV2Payload(int token, ResourceLocation targetItem, int targetCount,
                                 boolean approximate, boolean saturated,
                                 List<ItemDemand> demands) implements SprawlPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "shortfall_v2");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<ShortfallV2Payload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, ShortfallV2Payload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ShortfallV2Payload::token,
                    ResourceLocation.STREAM_CODEC, ShortfallV2Payload::targetItem,
                    ByteBufCodecs.VAR_INT, ShortfallV2Payload::targetCount,
                    ByteBufCodecs.BOOL, ShortfallV2Payload::approximate,
                    ByteBufCodecs.BOOL, ShortfallV2Payload::saturated,
                    ItemDemand.LONG_STREAM_CODEC.apply(ByteBufCodecs.list()), ShortfallV2Payload::demands,
                    ShortfallV2Payload::new);

    @Override
    public CustomPacketPayload.Type<ShortfallV2Payload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    // Same field order as the 1.20.5+ codec: token, target, targetCount, approximate, saturated, demands.
    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(token);
        buf.writeResourceLocation(targetItem);
        buf.writeVarInt(targetCount);
        buf.writeBoolean(approximate);
        buf.writeBoolean(saturated);
        buf.writeCollection(demands, (b, demand) -> demand.writeLong(b));
    }

    public static ShortfallV2Payload read(FriendlyByteBuf buf) {
        return new ShortfallV2Payload(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readList(ItemDemand::readLong));
    }*/
    //?}
}
