package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side registry of each player's active craft job. v1 allows a single job per
 * player: enqueueing while one is running is rejected.
 *
 * <p>Loader entry points wire {@link #tick(ServerPlayer)} into their per-player
 * end-of-tick hook, {@link #clear(UUID)} into disconnect handling, and
 * {@link #clearAll()} into server shutdown.
 *
 * <p>Progress is streamed to the client's flyout toast via {@link CraftProgressPayload};
 * terminal events additionally get a chat line for the record.
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
        if (ACTIVE.putIfAbsent(player.getUUID(), job) != null) {
            return false;
        }
        sync(player, job, CraftProgressPayload.State.CRAFTING, ItemStack.EMPTY);
        return true;
    }

    /** @return the cancelled job, if there was one. The caller reports to the player. */
    public static Optional<CraftJob> cancel(UUID playerId) {
        return Optional.ofNullable(ACTIVE.remove(playerId));
    }

    /** Toast sync for a manual cancel (command path, which has the player at hand). */
    public static void syncCancelled(ServerPlayer player, CraftJob job) {
        sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
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
            sync(player, job, CraftProgressPayload.State.PAUSED, ItemStack.EMPTY);
            return;
        }
        switch (CraftExecutor.craftOnce(player, job.currentStep().recipeId())) {
            case CraftExecutor.CraftResult.Success success -> {
                job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.FINISHED, success.crafted());
                    player.displayClientMessage(Component.translatable("sprawlcrafting.craft.finished",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.GREEN), false);
                } else {
                    sync(player, job, CraftProgressPayload.State.CRAFTING, success.crafted());
                }
            }
            case CraftExecutor.CraftResult.MissingIngredient missing -> {
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                player.displayClientMessage(Component.translatable("sprawlcrafting.craft.missing",
                        job.targetResult().getHoverName(), missing.missing()).withStyle(ChatFormatting.RED), false);
            }
            case CraftExecutor.CraftResult.RecipeGone gone -> {
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                player.displayClientMessage(Component.translatable("sprawlcrafting.craft.recipe_gone",
                        job.targetResult().getHoverName()).withStyle(ChatFormatting.RED), false);
            }
        }
    }

    /** Vanilla send path — identical bytes to what both loaders' helpers produce. */
    private static void sync(ServerPlayer player, CraftJob job,
                             CraftProgressPayload.State state, ItemStack current) {
        player.connection.send(new ClientboundCustomPayloadPacket(new CraftProgressPayload(
                state, job.targetResult().copy(), current.copy(), job.craftsDone(), job.totalCrafts())));
    }
}
