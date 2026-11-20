package com.legoj15.sprawlcrafting.network;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import com.legoj15.sprawlcrafting.Constants;

/**
 * C2S: the player confirmed a deferred craft whose plan needs the player's protected stacks
 * (enchanted and/or renamed, never damaged; issue #40) — the plain pool fell short, and the preview
 * said which special items would be used. Same shape as {@link StartDeferredCraftPacket}: the server
 * re-plans authoritatively with the protected tier enabled, so this only carries the recipe id.
 *
 * <p>Rides its own channel ({@code sprawlcrafting:start_craft_protected}) rather than a fourth id on
 * the main one, for the same reason as {@link CancelCraftPacket}: the main channel's ids 0-2 are
 * frozen, and an older peer cannot be handed a new one. The channel's presence on the server is the
 * client's only offer gate — an older server never sees a protected request at all.
 */
public final class StartProtectedCraftPacket {

    public static final ResourceLocation ID = Constants.id("start_craft_protected");

    private final ResourceLocation recipeId;

    public StartProtectedCraftPacket(ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    public ResourceLocation recipeId() {
        return recipeId;
    }

    public void write(PacketBuffer buf) {
        buf.writeResourceLocation(recipeId);
    }

    public static StartProtectedCraftPacket read(PacketBuffer buf) {
        return new StartProtectedCraftPacket(buf.readResourceLocation());
    }
}
