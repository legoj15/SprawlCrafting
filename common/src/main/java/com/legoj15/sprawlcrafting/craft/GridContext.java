package com.legoj15.sprawlcrafting.craft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;

/**
 * The crafting grid a deferred-craft request was made from. Whole-chain gating
 * (DESIGN.md): every step of a job — intermediates and final — must fit this grid.
 * Steps that need the full grid additionally pause at execution time unless a crafting
 * table is within the player's block-interaction reach.
 */
public enum GridContext {
    /** The player inventory's 2×2 grid. */
    INVENTORY(2, 2),
    /** A crafting table's 3×3 grid. */
    CRAFTING_TABLE(3, 3);

    private final int width;
    private final int height;

    GridContext(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The grid the player is looking at right now: 3×3 with a crafting table open, else 2×2. */
    public static GridContext current(ServerPlayer player) {
        return player.containerMenu instanceof CraftingMenu ? CRAFTING_TABLE : INVENTORY;
    }
}
