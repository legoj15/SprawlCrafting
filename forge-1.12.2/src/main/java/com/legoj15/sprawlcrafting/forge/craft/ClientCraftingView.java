package com.legoj15.sprawlcrafting.forge.craft;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side record of what each client has told us over the v2 channel: whether it is
 * v2-capable at all (it answered the login hello — an older jar never does), whether it
 * understands the long progress counts (the hello's capability byte, issue #46), and which
 * crafting screen it says it has open. The screen state exists for the one case the server can't
 * see itself — the 2x2 inventory screen ({@code openContainer} is the inventory container whether
 * or not its GUI is open). Absence always means the safe default: not capable / no counts /
 * no crafting screen (the hand-off then falls back to auto-crafting, the progress sync to its
 * int form — both the pre-v2 behavior).
 *
 * <p>The screen state is aggressively invalidated — at every job start and whenever the server
 * processes any container open/close for the player — because it is client-reported and
 * latency-stale by nature: a stale "2x2 open" would let the final-step hand-off lay items into a
 * grid the player is no longer looking at.
 */
public final class ClientCraftingView {

    /** Players whose client answered the v2 hello. */
    private static final Set<UUID> CAPABLE = ConcurrentHashMap.newKeySet();

    /** Players whose hello also carried the progress-counts bit (issue #46). */
    private static final Set<UUID> PROGRESS_COUNTS = ConcurrentHashMap.newKeySet();

    /** Packed grid size (width * 31 + height) per player; absent = no crafting screen open. */
    private static final Map<UUID, Integer> OPEN_GRIDS = new ConcurrentHashMap<UUID, Integer>();

    private ClientCraftingView() {
    }

    /**
     * Records the hello's answer: the client speaks the v2 channel at all, and whether it
     * advertised the long progress counts (issue #46). An empty pre-capability hello sets the
     * first and not the second — the two must stay apart, because the counts message may only
     * reach a client that named the bit.
     */
    public static void markCapable(UUID playerId, boolean progressCounts) {
        CAPABLE.add(playerId);
        if (progressCounts) {
            PROGRESS_COUNTS.add(playerId);
        }
    }

    /** Whether this player's client asked for the real long progress counts (issue #46). */
    public static boolean progressCounts(UUID playerId) {
        return PROGRESS_COUNTS.contains(playerId);
    }

    /** Whether this player's client speaks the v2 channel (hello answered). */
    public static boolean capable(UUID playerId) {
        return CAPABLE.contains(playerId);
    }

    public static void update(UUID playerId, int gridWidth, int gridHeight) {
        if (gridWidth <= 0 || gridHeight <= 0) {
            OPEN_GRIDS.remove(playerId);
        } else {
            OPEN_GRIDS.put(playerId, Integer.valueOf(gridWidth * 31 + gridHeight));
        }
    }

    /** Whether the client reports its 2x2 inventory crafting screen open right now. */
    public static boolean inventoryScreenOpen(UUID playerId) {
        Integer packed = OPEN_GRIDS.get(playerId);
        return packed != null && packed.intValue() == 2 * 31 + 2;
    }

    /** Drops only the screen state (back to the safe "no screen" default), keeping capability. */
    public static void clearScreen(UUID playerId) {
        OPEN_GRIDS.remove(playerId);
    }

    /** Full reset on logout. */
    public static void clear(UUID playerId) {
        CAPABLE.remove(playerId);
        PROGRESS_COUNTS.remove(playerId);
        OPEN_GRIDS.remove(playerId);
    }
}
