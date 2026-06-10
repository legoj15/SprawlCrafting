package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side registry of each player's active craft job. v1 allows a single job per
 * player: enqueueing while one is running is rejected (the client greys out the option).
 *
 * <p>Loader entry points wire {@link #tick(ServerPlayer)} into their end-of-server-tick
 * player loop, and {@link #clear(UUID)} into disconnect handling.
 */
public final class CraftQueueManager {

    private static final Map<UUID, CraftJob> ACTIVE = new ConcurrentHashMap<>();

    private CraftQueueManager() {
    }

    public static Optional<CraftJob> activeJob(UUID playerId) {
        return Optional.ofNullable(ACTIVE.get(playerId));
    }

    /** @return false if the player already has a job running (single-job rule). */
    public static boolean start(ServerPlayer player, CraftJob job) {
        return ACTIVE.putIfAbsent(player.getUUID(), job) == null;
    }

    public static void cancel(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    /** Advances the player's job by one tick; performs a craft when one comes due. */
    public static void tick(ServerPlayer player) {
        CraftJob job = ACTIVE.get(player.getUUID());
        if (job == null) {
            return;
        }
        if (job.tick()) {
            // TODO: execute job.currentStep() against the player's inventory:
            //  - re-check ingredients; on shortfall, cancel the job and notify (graceful fail)
            //  - consume ingredients, insert results as real items (drop if inventory is full)
            //  - job.onCraftPerformed(); sync progress to the client HUD
        }
        if (job.isFinished()) {
            ACTIVE.remove(player.getUUID());
        }
    }
}
