package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

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
 * C2S (26.x only): first click on a yellow recipe-book entry asks the server to plan it so the
 * intermediate-step breakdown can be shown in the button tooltip. The client cannot build the
 * plan itself on 26.x (no recipe graph), so the server replies with {@link CraftPreviewPayload}.
 *
 * @param displayId the {@code RecipeDisplayId} index of the clicked entry
 */
public record RequestCraftPreviewPayload(int displayId) implements SprawlPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "request_preview");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<RequestCraftPreviewPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, RequestCraftPreviewPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RequestCraftPreviewPayload::displayId,
                    RequestCraftPreviewPayload::new);

    @Override
    public CustomPacketPayload.Type<RequestCraftPreviewPayload> type() {
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
    }

    public static RequestCraftPreviewPayload read(FriendlyByteBuf buf) {
        return new RequestCraftPreviewPayload(buf.readVarInt());
    }*/
    //?}
}
