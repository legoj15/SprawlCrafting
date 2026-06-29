package com.legoj15.sprawlcrafting.forge.craft;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerWorkbench;

/**
 * The crafting grid a deferred-craft request was made from. Whole-chain gating (DESIGN.md):
 * every step of a job — intermediates and final — must fit this grid. Steps that need the
 * full grid additionally pause at execution time unless a crafting table is within the
 * player's reach.
 *
 * <p>1.12.2 has no generic "grid size" accessor on {@code Container} the way modern versions
 * do, so {@link #current} keys on the open container type: a {@link ContainerWorkbench} is the
 * 3x3 table, anything else (including the player inventory's own 2x2) is treated as 2x2. A
 * modded 3x3 table that is not a {@code ContainerWorkbench} is therefore seen as 2x2 — the same
 * class of limitation the modern tree documents for modded tables, one notch earlier.
 */
public enum GridContext {
    /** The player inventory's 2x2 grid. */
    INVENTORY(2, 2),
    /** A crafting table's 3x3 grid. */
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

    /** The grid the player is looking at right now, by open-container type. */
    public static GridContext current(EntityPlayer player) {
        if (player.openContainer instanceof ContainerWorkbench) {
            return CRAFTING_TABLE;
        }
        return INVENTORY;
    }
}
