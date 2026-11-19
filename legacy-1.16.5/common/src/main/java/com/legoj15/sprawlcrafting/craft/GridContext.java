package com.legoj15.sprawlcrafting.craft;

import net.minecraft.entity.player.ServerPlayerEntity;

/**
 * The crafting grid a deferred-craft request was made from. Whole-chain gating (DESIGN.md): every
 * step of a job — intermediates and final — must fit this grid. Steps that need the full grid
 * additionally pause at execution time unless a crafting table is within the player's reach.
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

    /** Grid context from a recipe-book menu's grid size (3×3 or larger is a crafting table). */
    public static GridContext forSize(int width, int height) {
        return width >= 3 && height >= 3 ? CRAFTING_TABLE : INVENTORY;
    }

    /**
     * The grid the player is looking at right now, keyed on the open menu's grid size rather than
     * {@code CraftingMenu}, so a modded recipe-book menu exposing a 3×3 grid plans correctly. A crafting
     * station ({@link StationStorages}, the AE2 Crafting Terminal) has a 3×3 grid but no recipe book, so it
     * is recognised by its provider instead, and only while it is usable
     * ({@link StationStorages#hasUsableStation}): an open terminal whose network is offline is no grid.
     */
    public static GridContext current(ServerPlayerEntity player) {
        if (StationStorages.hasUsableStation(player)) {
            return CRAFTING_TABLE;
        }
        CraftingGrids.Grid grid = CraftingGrids.find(player.containerMenu);
        return grid == null ? INVENTORY : forSize(grid.width(), grid.height());
    }
}
