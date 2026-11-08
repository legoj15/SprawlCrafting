package com.legoj15.sprawlcrafting.network;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import com.legoj15.sprawlcrafting.Constants;

/**
 * C2S: the player confirmed a yellow (deferred-craftable) recipe in the recipe book or JEI. The server
 * re-plans authoritatively from its own state, so this only carries the recipe id — the grid context
 * comes from the menu the player has open server-side.
 */
public final class StartDeferredCraftPacket {

    public static final ResourceLocation ID = Constants.id("start_craft");

    private final ResourceLocation recipeId;

    public StartDeferredCraftPacket(ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public void write(PacketBuffer buf) {
        buf.writeResourceLocation(recipeId);
    }

    public static StartDeferredCraftPacket read(PacketBuffer buf) {
        return new StartDeferredCraftPacket(buf.readResourceLocation());
    }
}
