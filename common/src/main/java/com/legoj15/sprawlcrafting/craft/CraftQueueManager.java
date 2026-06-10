package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side registry of each player's active craft job. v1 allows a single job per
 * player: enqueueing while one is running is rejected.
 *
 * <p>Loader entry points wire {@link #tick(ServerPlayer)} into their per-player
 * end-of-tick hook, {@link #clear(UUID)} into disconnect handling, and
 * {@link #clearAll()} into server shutdown.
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

    /** @return the cancelled job, if there was one. */
    public static Optional<CraftJob> cancel(UUID playerId) {
        return Optional.ofNullable(ACTIVE.remove(playerId));
    }

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /** Advances the player's job by one tick; performs a craft when one comes due. */
    public static void tick(ServerPlayer player) {
        CraftJob job = ACTIVE.get(player.getUUID());
        if (job == null || !job.tick()) {
            return;
        }
        // 3×3 steps only run with a crafting table in reach; re-check every half second.
        if (job.currentStep().needsFullGrid() && !CraftingTableReach.isInReach(player)) {
            job.holdForRetry();
            player.displayClientMessage(Component.translatable("sprawlcrafting.craft.paused_no_table",
                    job.targetResult().getHoverName()).withStyle(ChatFormatting.GOLD), true);
            return;
        }
        switch (CraftExecutor.craftOnce(player, job.currentStep().recipeId())) {
            case CraftExecutor.CraftResult.Success success -> {
                job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUUID());
                    finish(player, Component.translatable("sprawlcrafting.craft.finished",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.GREEN));
                } else {
                    player.displayClientMessage(Component.translatable("sprawlcrafting.craft.progress",
                            job.targetResult().getHoverName(), success.crafted().getHoverName(),
                            job.craftsDone(), job.totalCrafts()), true);
                }
            }
            case CraftExecutor.CraftResult.MissingIngredient missing -> {
                ACTIVE.remove(player.getUUID());
                finish(player, Component.translatable("sprawlcrafting.craft.missing",
                        job.targetResult().getHoverName(), missing.missing()).withStyle(ChatFormatting.RED));
            }
            case CraftExecutor.CraftResult.RecipeGone gone -> {
                ACTIVE.remove(player.getUUID());
                finish(player, Component.translatable("sprawlcrafting.craft.recipe_gone",
                        job.targetResult().getHoverName()).withStyle(ChatFormatting.RED));
            }
        }
    }

    /** Terminal message: chat for the record, action bar to overwrite lingering progress text. */
    private static void finish(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
        player.displayClientMessage(message, true);
    }
}
