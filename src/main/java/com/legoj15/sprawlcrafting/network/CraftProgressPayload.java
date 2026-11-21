package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
//? if >=1.20.5 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * S2C: live state of the player's deferred craft job, driving the client's progress
 * toast. Sent on start, after every craft of a small ({@code CraftJob.BULK_THRESHOLD} or fewer crafts)
 * step, once per half second and at each step boundary while a large step runs in bursts, on pause
 * retries, and on terminal transitions; the client just renders the latest snapshot.
 *
 * <p>The counts are {@code long} in memory (a deep compression chain runs past {@link Integer#MAX_VALUE}
 * crafts) but VarInt on this frozen mod-1.0.3 wire: the server scales them into int range with
 * {@code CraftJob.wireDone/wireTotal} before building the payload, and a client that registered
 * {@link CraftProgressCountsPayload} gets the real longs there instead (issue #46).
 *
 * @param current the item produced by the in-flight step (EMPTY when paused/terminal)
 */
public record CraftProgressPayload(State state, ItemStack target, ItemStack current,
                                   long done, long total) implements SprawlPayload {

    public enum State {
        // Wire format is ordinal-keyed (STATE_CODEC below) and PayloadCodecTest pins that
        // out-of-range ordinals throw — only ever APPEND new states here, never reorder.
        // PAUSED_STATION (a station-dependent job waiting for its crafting station to be reopened) is
        // newer than the 1.0.x clients: the server only sends it to a client that registered
        // ProgressStatesV2Payload and downgrades it to PAUSED for any other (CraftQueueManager.sync).
        CRAFTING, PAUSED, FINISHED, CANCELLED, READY_IN_GRID, PAUSED_STATION;

        /**
         * The job is over for the client's purposes — the toast lingers, then slides out.
         * READY_IN_GRID is terminal: the server has handed the final craft off to the open
         * crafting grid and freed the job slot; the player just grabs the result.
         */
        public boolean isTerminal() {
            return this == FINISHED || this == CANCELLED || this == READY_IN_GRID;
        }
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "craft_progress");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<CraftProgressPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    // Package-visible: CraftProgressCountsPayload writes the same state head on its wire (issue #46).
    static final StreamCodec<ByteBuf, State> STATE_CODEC =
            ByteBufCodecs.idMapper(i -> State.values()[i], State::ordinal);

    // The long count fields through the frozen VarInt wire: exact in int range, and an accidental
    // over-int send throws (toIntExact) instead of silently truncating — a job that big must go out
    // as CraftProgressCountsPayload (issue #46).
    private static final StreamCodec<ByteBuf, Long> VAR_INT_COUNT =
            ByteBufCodecs.VAR_INT.map(Integer::longValue, Math::toIntExact);

    // ItemStacks need the registry-aware buffer on 1.20.5+, which is why this payload keeps its
    // composite codec rather than sharing the pre-1.20.5 write/read pair below.
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftProgressPayload> STREAM_CODEC =
            StreamCodec.composite(
                    STATE_CODEC, CraftProgressPayload::state,
                    ItemStack.OPTIONAL_STREAM_CODEC, CraftProgressPayload::target,
                    ItemStack.OPTIONAL_STREAM_CODEC, CraftProgressPayload::current,
                    VAR_INT_COUNT, CraftProgressPayload::done,
                    VAR_INT_COUNT, CraftProgressPayload::total,
                    CraftProgressPayload::new);

    @Override
    public CustomPacketPayload.Type<CraftProgressPayload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    // Same field order as the 1.20.5+ codec: state ordinal (VarInt), target, current, done, total.
    // The counts are long in memory but VarInt here (frozen mod-1.0.3 wire), so an accidental
    // over-int send throws (toIntExact) rather than truncating — see VAR_INT_COUNT above.
    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(state.ordinal());
        buf.writeItem(target);
        buf.writeItem(current);
        buf.writeVarInt(Math.toIntExact(done));
        buf.writeVarInt(Math.toIntExact(total));
    }

    public static CraftProgressPayload read(FriendlyByteBuf buf) {
        return new CraftProgressPayload(State.values()[buf.readVarInt()], buf.readItem(), buf.readItem(),
                buf.readVarInt(), buf.readVarInt());
    }*/
    //?}
}
