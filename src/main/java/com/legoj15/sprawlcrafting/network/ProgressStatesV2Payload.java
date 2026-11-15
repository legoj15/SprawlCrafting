package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.resources.ResourceLocation;
//? if >=1.20.5 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * S2C capability marker, never actually sent: a client that registers this channel can decode the
 * {@link CraftProgressPayload.State}s appended after the original five ({@code PAUSED_STATION}). The
 * progress state travels as a bare ordinal that a 1.0.x client decodes with {@code values()[i]}, so the
 * server checks {@code canReceive(player, ProgressStatesV2Payload.ID)} before sending a newer state and
 * sends the nearest original one otherwise ({@code CraftQueueManager.sync}). Same idea as
 * {@code ShortfallV2Payload}'s new id, without duplicating the progress payload.
 *
 * <p>Registered (optional, empty handler) on every 1.20.5+ loader. Pre-1.20.5 lines do not register it,
 * so their server always downgrades; no station provider exists on those lines today.
 */
public record ProgressStatesV2Payload() implements SprawlPayload {

    public static final ProgressStatesV2Payload INSTANCE = new ProgressStatesV2Payload();

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "progress_states_v2");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<ProgressStatesV2Payload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, ProgressStatesV2Payload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<ProgressStatesV2Payload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
    }

    public static ProgressStatesV2Payload read(FriendlyByteBuf buf) {
        return INSTANCE;
    }*/
    //?}
}
