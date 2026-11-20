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
 * C2S: the player pressed the cancel key ({@code CancelCraftKey}) to stop their running or
 * paused deferred craft. Carries nothing — the job cancelled is the sender's own — so the
 * client only sends it while {@code ClientJobTracker} shows an active job, and only when the
 * server negotiated the channel ({@code IPlatformHelper.canSendToServer}): on an older or
 * modless server the key silently does nothing. On Forge 1.20.1 it rides its own channel
 * rather than the shared one, whose discriminator space is frozen (see {@code ForgeNetwork}).
 */
public record CancelCraftPayload() implements SprawlPayload {

    public static final CancelCraftPayload INSTANCE = new CancelCraftPayload();

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cancel_craft");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<CancelCraftPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, CancelCraftPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<CancelCraftPayload> type() {
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

    public static CancelCraftPayload read(FriendlyByteBuf buf) {
        return INSTANCE;
    }*/
    //?}
}
