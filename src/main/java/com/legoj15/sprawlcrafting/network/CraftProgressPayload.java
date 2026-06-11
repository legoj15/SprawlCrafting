package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * S2C: live state of the player's deferred craft job, driving the client's progress
 * toast. Sent on start, after every component craft, on pause retries, and on terminal
 * transitions; the client just renders the latest snapshot.
 *
 * @param current the item produced by the in-flight step (EMPTY when paused/terminal)
 */
public record CraftProgressPayload(State state, ItemStack target, ItemStack current,
                                   int done, int total) implements CustomPacketPayload {

    public enum State {
        CRAFTING, PAUSED, FINISHED, CANCELLED;

        public boolean isTerminal() {
            return this == FINISHED || this == CANCELLED;
        }
    }

    public static final CustomPacketPayload.Type<CraftProgressPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "craft_progress"));

    private static final StreamCodec<ByteBuf, State> STATE_CODEC =
            ByteBufCodecs.idMapper(i -> State.values()[i], State::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftProgressPayload> STREAM_CODEC =
            StreamCodec.composite(
                    STATE_CODEC, CraftProgressPayload::state,
                    ItemStack.OPTIONAL_STREAM_CODEC, CraftProgressPayload::target,
                    ItemStack.OPTIONAL_STREAM_CODEC, CraftProgressPayload::current,
                    ByteBufCodecs.VAR_INT, CraftProgressPayload::done,
                    ByteBufCodecs.VAR_INT, CraftProgressPayload::total,
                    CraftProgressPayload::new);

    @Override
    public CustomPacketPayload.Type<CraftProgressPayload> type() {
        return TYPE;
    }
}
