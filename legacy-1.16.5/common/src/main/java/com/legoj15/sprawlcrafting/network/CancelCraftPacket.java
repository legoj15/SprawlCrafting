package com.legoj15.sprawlcrafting.network;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import com.legoj15.sprawlcrafting.Constants;

/**
 * C2S: cancel the player's running or paused deferred craft (the "Cancel deferred craft" keybind).
 * No fields — the sending player identifies the job, and
 * {@link com.legoj15.sprawlcrafting.craft.CraftQueueManager#cancel} is a silent no-op when there is
 * none, so a stray press is harmless. Rides its own channel ({@code sprawlcrafting:cancel_craft})
 * rather than a fourth id on the main one: the main channel's ids 0-2 are frozen, and an older peer
 * cannot be handed id 3.
 */
public final class CancelCraftPacket {

    public static final ResourceLocation ID = Constants.id("cancel_craft");

    public void write(PacketBuffer buf) {
        // Nothing to say: the sender alone identifies the job.
    }

    public static CancelCraftPacket read(PacketBuffer buf) {
        return new CancelCraftPacket();
    }
}
