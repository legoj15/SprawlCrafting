package com.legoj15.sprawlcrafting.craft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.RecipeBookMenu;

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

    /**
     * The grid the player is looking at right now, keyed on the open menu's grid size —
     * 3×3 (or larger) is a crafting table, else the 2×2 inventory grid. Matching on grid
     * dimensions rather than {@code CraftingMenu} keeps this in lockstep with the client's
     * {@code DeferredCraftableCache.gridFor}, including modded crafting-table menus.
     */
    public static GridContext current(ServerPlayer player) {
        if (player.containerMenu instanceof RecipeBookMenu<?, ?> menu
                && menu.getGridWidth() >= 3 && menu.getGridHeight() >= 3) {
            return CRAFTING_TABLE;
        }
        return INVENTORY;
    }
}
