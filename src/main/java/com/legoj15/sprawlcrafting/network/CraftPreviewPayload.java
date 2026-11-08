package com.legoj15.sprawlcrafting.network;

import java.util.List;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
//? if >=1.20.5 {
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * S2C (26.x only): the server-built plan preview for a clicked yellow recipe-book entry — the
 * same intermediate-step breakdown the 1.21.1 client computes locally, but planned server-side
 * and streamed down. The client shows {@code lines} in the entry's tooltip and arms the
 * second-click confirm. An empty list means "no longer plannable" (inventory changed): the
 * client drops the pending preview.
 *
 * @param displayId the entry these lines describe (matches the client's pending RecipeDisplayId)
 * @param lines     ready-to-render tooltip lines (header, per-intermediate totals, confirm hint)
 */
public record CraftPreviewPayload(int displayId, List<Component> lines) implements SprawlPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "craft_preview");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<CraftPreviewPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftPreviewPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CraftPreviewPayload::displayId,
                    ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list()), CraftPreviewPayload::lines,
                    CraftPreviewPayload::new);

    @Override
    public CustomPacketPayload.Type<CraftPreviewPayload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(displayId);
        buf.writeCollection(lines, FriendlyByteBuf::writeComponent);
    }

    public static CraftPreviewPayload read(FriendlyByteBuf buf) {
        return new CraftPreviewPayload(buf.readVarInt(), buf.readList(FriendlyByteBuf::readComponent));
    }*/
    //?}
}
