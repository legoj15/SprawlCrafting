package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the player confirmed a yellow-outlined recipe in the recipe book. The server
 * re-plans authoritatively from its own state (the client preview is informational),
 * so this only needs to carry the recipe id — the grid context comes from the menu the
 * player has open server-side.
 *
 * <p>Registered per loader (NeoForge {@code RegisterPayloadHandlersEvent}, Fabric
 * {@code PayloadTypeRegistry}); both route to
 * {@code CraftRequests.handleStartRequest} on the server thread.
 */
public record StartDeferredCraftPayload(ResourceLocation recipeId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StartDeferredCraftPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_craft"));

    public static final StreamCodec<ByteBuf, StartDeferredCraftPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, StartDeferredCraftPayload::recipeId,
                    StartDeferredCraftPayload::new);

    @Override
    public CustomPacketPayload.Type<StartDeferredCraftPayload> type() {
        return TYPE;
    }
}
