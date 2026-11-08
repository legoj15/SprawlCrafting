package com.legoj15.sprawlcrafting.network;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import com.legoj15.sprawlcrafting.Constants;

/**
 * C2S: the dimensions of the crafting grid the player currently has on screen, or {@code 0×0} when no
 * crafting screen is open. The server cannot otherwise observe the 2×2 inventory crafting screen
 * ({@code ServerPlayerEntity.containerMenu} is always the {@code InventoryMenu} whether or not the inventory
 * GUI is showing). Lets the server hand a deferred craft's last step off to the open grid. The client
 * only sends it while a job is active, and only on change.
 */
public final class CraftingScreenStatePacket {

    public static final ResourceLocation ID = Constants.id("crafting_screen_state");

    private final int gridWidth;
    private final int gridHeight;

    public CraftingScreenStatePacket(int gridWidth, int gridHeight) {
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
    }

    public int gridWidth() {
        return gridWidth;
    }

    public int gridHeight() {
        return gridHeight;
    }

    public void write(PacketBuffer buf) {
        buf.writeVarInt(gridWidth);
        buf.writeVarInt(gridHeight);
    }

    public static CraftingScreenStatePacket read(PacketBuffer buf) {
        return new CraftingScreenStatePacket(buf.readVarInt(), buf.readVarInt());
    }
}
