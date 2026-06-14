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
        // Wire format is ordinal-keyed (STATE_CODEC below) and PayloadCodecTest pins that
        // out-of-range ordinals throw — only ever APPEND new states here, never reorder.
        CRAFTING, PAUSED, FINISHED, CANCELLED, READY_IN_GRID;

        /**
         * The job is over for the client's purposes — the toast lingers, then slides out.
         * READY_IN_GRID is terminal: the server has handed the final craft off to the open
         * crafting grid and freed the job slot; the player just grabs the result.
         */
        public boolean isTerminal() {
            return this == FINISHED || this == CANCELLED || this == READY_IN_GRID;
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
