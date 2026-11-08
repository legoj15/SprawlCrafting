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
 */
public final class CraftProgressPacket {

    public static final ResourceLocation ID = Constants.id("craft_progress");

    public enum State {
        // Wire format is ordinal-keyed — only ever APPEND new states, never reorder.
        CRAFTING, PAUSED, FINISHED, CANCELLED, READY_IN_GRID;

        /** The job is over for the client's purposes — the toast lingers, then slides out. */
        public boolean isTerminal() {
            return this == FINISHED || this == CANCELLED || this == READY_IN_GRID;
        }
    }

    private final State state;
    private final ItemStack target;
    private final ItemStack current;
    private final int done;
    private final int total;

    public CraftProgressPacket(State state, ItemStack target, ItemStack current, int done, int total) {
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

    public int done() {
        return done;
    }

    public int total() {
        return total;
    }

    public void write(PacketBuffer buf) {
        buf.writeVarInt(state.ordinal());
        buf.writeItem(target);
        buf.writeItem(current);
        buf.writeVarInt(done);
        buf.writeVarInt(total);
    }

    public static CraftProgressPacket read(PacketBuffer buf) {
        int ordinal = buf.readVarInt();
        State[] states = State.values();
        if (ordinal < 0 || ordinal >= states.length) {
            throw new IllegalArgumentException("Unknown craft state ordinal " + ordinal);
        }
        return new CraftProgressPacket(states[ordinal], buf.readItem(), buf.readItem(),
                buf.readVarInt(), buf.readVarInt());
    }
}
