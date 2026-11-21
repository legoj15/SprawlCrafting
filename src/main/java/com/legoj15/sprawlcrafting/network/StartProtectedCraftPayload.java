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
 * C2S: the player confirmed a deferred craft whose plan needs the player's protected stacks
 * (enchanted and/or renamed, never damaged; issue #40) — the plain pool fell short, and the preview
 * said which special items would be used. Same shape as {@link StartDeferredCraftPayload}: the server
 * re-plans authoritatively with the protected tier enabled, so this only carries the recipe id.
 *
 * <p>Rides its own payload id ({@code sprawlcrafting:start_craft_protected}) rather than reusing the
 * plain start's, so an older peer that never registered it can be told apart: the client guards every
 * send with {@code canSendToServer(..., ID)} — an older server never sees a protected request at all,
 * and never offers the crafts either (the client's offer gate is the same check). On Forge 1.20.1,
 * whose main channel discriminators are frozen, it rides its own {@code SimpleChannel} like
 * {@link CancelCraftPayload} (see {@code ForgeNetwork}).
 */
public record StartProtectedCraftPayload(ResourceLocation recipeId) implements SprawlPayload {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_craft_protected");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<StartProtectedCraftPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, StartProtectedCraftPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, StartProtectedCraftPayload::recipeId,
                    StartProtectedCraftPayload::new);

    @Override
    public CustomPacketPayload.Type<StartProtectedCraftPayload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(recipeId);
    }

    public static StartProtectedCraftPayload read(FriendlyByteBuf buf) {
        return new StartProtectedCraftPayload(buf.readResourceLocation());
    }*/
    //?}
}
