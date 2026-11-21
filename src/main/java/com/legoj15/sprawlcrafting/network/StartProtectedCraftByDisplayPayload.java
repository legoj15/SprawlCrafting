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
 * C2S (26.x only): the protected counterpart of {@link StartDeferredCraftByDisplayPayload} — the
 * player confirmed a recipe-book entry whose plan needs the player's protected stacks (issue #40),
 * so the start request carries the entry's opaque {@code RecipeDisplayId} index and the server maps
 * it back and re-plans with the protected tier ({@code CraftRequests.handleStartRequest(…, true)}).
 * Registered only on 26.x, exactly like the plain by-display start: 1.21.1 clients hold the recipe
 * and send {@link StartProtectedCraftPayload} directly.
 *
 * @param displayId the {@code RecipeDisplayId} index the recipe book was showing
 */
public record StartProtectedCraftByDisplayPayload(int displayId) implements SprawlPayload {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_craft_protected_display");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<StartProtectedCraftByDisplayPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, StartProtectedCraftByDisplayPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StartProtectedCraftByDisplayPayload::displayId,
                    StartProtectedCraftByDisplayPayload::new);

    @Override
    public CustomPacketPayload.Type<StartProtectedCraftByDisplayPayload> type() {
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

    public static StartProtectedCraftByDisplayPayload read(FriendlyByteBuf buf) {
        return new StartProtectedCraftByDisplayPayload(buf.readVarInt());
    }*/
    //?}
}
