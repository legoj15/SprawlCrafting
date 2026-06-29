package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Reuses one client-side {@link CraftPlanner.Session} across the many JEI "+" validation queries
 * that happen while the player hovers a recipe list. The session (and its expensive producer index)
 * is rebuilt only when the player's main inventory or the open grid changes, keyed by a cheap
 * content signature. Client-only.
 */
public final class ClientPlanCache {

    private ClientPlanCache() {
    }

    private static CraftPlanner.Session session;
    private static int signature = Integer.MIN_VALUE;
    private static GridContext cachedGrid;

    public static CraftPlanner.Session get(EntityPlayer player, GridContext grid) {
        int sig = inventorySignature(player);
        if (session == null || sig != signature || grid != cachedGrid) {
            session = CraftPlanner.session(player, grid);
            signature = sig;
            cachedGrid = grid;
        }
        return session;
    }

    private static int inventorySignature(EntityPlayer player) {
        int hash = 1;
        for (int i = 0; i < CraftPlanner.MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = player.inventory.mainInventory.get(i);
            int slot = stack.isEmpty()
                    ? 0
                    : (Item.getIdFromItem(stack.getItem()) * 31 + stack.getMetadata()) * 31 + stack.getCount();
            hash = 31 * hash + slot;
        }
        return hash;
    }
}
