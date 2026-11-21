package com.legoj15.sprawlcrafting.network;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;

import com.legoj15.sprawlcrafting.Constants;

/**
 * S2C: live state of the player's deferred craft job, driving the client's progress toast. Sent on
 * start, after every craft of a small ({@code CraftJob.BULK_THRESHOLD} or fewer crafts) step, once per
 * half second and at each step boundary while a large step runs in bursts, on pause retries, and on
 * terminal transitions; the client just renders the latest snapshot. 1.16.5 port of the modern {@code CraftProgressPayload} (same fields,
 * same ordinal-keyed state wire format).
 *
 * <p>The craft counts travel in one of two layouts, picked by {@code CraftQueueManager.sync}: the
 * main channel's {@link #write} (mod-1.0.3 bytes, counts as VarInt) for any client, or the counts
 * channel's {@link #writeCounts} (counts as VarLong) for a client that registered {@link #COUNTS_ID}
 * — an over-int job otherwise reaches such a client scaled to "x / 2.15B" (issue #46).
 */
public final class CraftProgressPacket {

    public static final ResourceLocation ID = Constants.id("craft_progress");

    /**
     * The counts channel (issue #46): the same progress snapshot, but {@code done}/{@code total} as
     * VarLong so an over-int job shows its real counts. Its presence on the client IS the capability —
     * the same marker-channel idea as {@link ProgressStatesV2} for the appended states: a client that
     * registered it decodes the long layout, any other keeps getting the main channel's exact old bytes.
     */
    public static final ResourceLocation COUNTS_ID = Constants.id("craft_progress_counts");

    public enum State {
        // Wire format is ordinal-keyed — only ever APPEND new states, never reorder. PAUSED_STATION (a
        // station-dependent job waiting for its crafting station to be reopened) is beyond what a mod-1.0.x
        // client decodes, so the server sends it only to a client that has the progress-states-v2 marker
        // channel and downgrades it to PAUSED for any other (CraftQueueManager.sync).
        CRAFTING, PAUSED, FINISHED, CANCELLED, READY_IN_GRID, PAUSED_STATION;

        /** The job is over for the client's purposes — the toast lingers, then slides out. */
        public boolean isTerminal() {
            return this == FINISHED || this == CANCELLED || this == READY_IN_GRID;
        }
    }

    private final State state;
    private final ItemStack target;
    private final ItemStack current;
    private final long done;
    private final long total;

    public CraftProgressPacket(State state, ItemStack target, ItemStack current, long done, long total) {
        this.state = state;
        this.target = target;
        this.current = current;
        this.done = done;
        this.total = total;
    }

    public State state() {
        return state;
    }

    public ItemStack target() {
        return target;
    }

    /** The item produced by the in-flight step (EMPTY when paused/terminal). */
    public ItemStack current() {
        return current;
    }

    public long done() {
        return done;
    }

    public long total() {
        return total;
    }

    /** The mod-1.0.3 main-channel layout — byte-identical to what a mod-1.0.x peer already decodes. */
    public void write(PacketBuffer buf) {
        buf.writeVarInt(state.ordinal());
        buf.writeItem(target);
        buf.writeItem(current);
        // Only ever called with int-range counts: the server builds the legacy packet from
        // CraftJob.wireDone/wireTotal, which scale an over-int job down. toIntExact turns an
        // accidental real count into a loud send-time error instead of a silently wrapped negative.
        buf.writeVarInt(Math.toIntExact(done));
        buf.writeVarInt(Math.toIntExact(total));
    }

    public static CraftProgressPacket read(PacketBuffer buf) {
        return new CraftProgressPacket(state(buf.readVarInt()), buf.readItem(), buf.readItem(),
                buf.readVarInt(), buf.readVarInt());
    }

    /** The counts-channel layout (issue #46): same header, but {@code done}/{@code total} as VarLong. */
    public void writeCounts(PacketBuffer buf) {
        buf.writeVarInt(state.ordinal());
        buf.writeItem(target);
        buf.writeItem(current);
        buf.writeVarLong(done);
        buf.writeVarLong(total);
    }

    public static CraftProgressPacket readCounts(PacketBuffer buf) {
        return new CraftProgressPacket(state(buf.readVarInt()), buf.readItem(), buf.readItem(),
                buf.readVarLong(), buf.readVarLong());
    }

    private static State state(int ordinal) {
        State[] states = State.values();
        if (ordinal < 0 || ordinal >= states.length) {
            throw new IllegalArgumentException("Unknown craft state ordinal " + ordinal);
        }
        return states[ordinal];
    }
}
