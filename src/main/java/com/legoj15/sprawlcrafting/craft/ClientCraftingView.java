package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side record of the crafting grid each player currently has on screen, fed by the
 * client's {@code CraftingScreenStatePayload}. Used by {@link CraftQueueManager} to decide
 * whether a deferred craft's final step can be handed off to an open grid for the player
 * to grab, rather than auto-crafted into the inventory.
 *
 * <p>Absent entry = no crafting screen open (the common case). Cleared on disconnect so a
 * stale "grid open" claim can't leak into a later session.
 */
public final class ClientCraftingView {

    private static final Map<UUID, GridContext> OPEN = new ConcurrentHashMap<>();

    private ClientCraftingView() {
    }

    /** Apply a reported open-grid size; {@code 0×0} (or sub-2×2) means no crafting screen. */
    public static void update(UUID playerId, int gridWidth, int gridHeight) {
        if (gridWidth >= 3 && gridHeight >= 3) {
            OPEN.put(playerId, GridContext.CRAFTING_TABLE);
        } else if (gridWidth >= 2 && gridHeight >= 2) {
            OPEN.put(playerId, GridContext.INVENTORY);
        } else {
            OPEN.remove(playerId);
        }
    }

    /** The grid the player reports having open, or {@code null} if none. */
    public static GridContext open(ServerPlayer player) {
        return OPEN.get(player.getUUID());
    }

    public static void clear(UUID playerId) {
        OPEN.remove(playerId);
    }

    public static void clearAll() {
        OPEN.clear();
    }
}
