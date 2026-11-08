package com.legoj15.sprawlcrafting.network;

//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;*/
//?}

/**
 * The mod's payload contract, the one type the platform layer's send methods take
 * ({@code IPlatformHelper.sendToServer} / {@code sendToPlayer}).
 *
 * <p>On MC 1.20.5+ it is just vanilla's {@code CustomPacketPayload}: every payload carries a
 * {@code TYPE} + {@code STREAM_CODEC}, and both loaders' networking APIs take the payload object
 * directly. Before 1.20.5 there are no payload objects on the wire, only a {@code ResourceLocation}
 * channel id plus a raw {@code FriendlyByteBuf}, so each payload instead exposes its channel
 * {@link #id()} and a {@link #write} (paired with a static {@code read(FriendlyByteBuf)} on the
 * record). A pre-1.20.5 platform layer (Fabric's channel API, Forge's channel API) registers one
 * receiver per {@code ID} that calls {@code read}, and sends by calling {@code write} into a fresh
 * buffer — so the version split lives here and in the platform helpers, not in callers.
 *
 * <p>Every payload's {@code ID} constant is the channel id on all versions ({@code TYPE.id()} on
 * 1.20.5+), so {@code canReceive}/{@code canSendToServer} checks are written once against it.
 */
//? if >=1.20.5 {
public interface SprawlPayload extends CustomPacketPayload {
}
//?} else {
/*public interface SprawlPayload {

    // The channel this payload travels on (the payload's static ID constant).
    ResourceLocation id();

    // Serializes the payload's fields; the record's static read(FriendlyByteBuf) is the inverse.
    void write(FriendlyByteBuf buf);
}*/
//?}
