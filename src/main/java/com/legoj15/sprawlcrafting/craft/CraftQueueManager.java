package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.platform.Services;

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

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
        ClientCraftingView.clear(playerId);
    }

    public static void clearAll() {
        ACTIVE.clear();
        ClientCraftingView.clearAll();
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
        // If the final craft is due and the player has a compatible crafting grid open, lay the
        // recipe out in that grid for them to grab (a real vanilla craft) instead of auto-crafting
        // it. Any case that can't cleanly hand off falls through to the normal auto-craft below.
        if (job.isFinalStep() && job.isLastCraftOfStep() && canHandOff(player, job.currentStep())) {
            switch (CraftExecutor.tryFillFinalGrid(player, job.currentStep().recipeId())) {
                case CraftExecutor.FillOutcome.Filled filled -> {
                    job.onCraftPerformed();
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.READY_IN_GRID, filled.result());
                    notify(player, Component.translatable("sprawlcrafting.craft.ready_in_grid",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.GREEN));
                    return;
                }
                case CraftExecutor.FillOutcome.RecipeGone gone -> {
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                    notify(player, Component.translatable("sprawlcrafting.craft.recipe_gone",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.RED));
                    return;
                }
                case CraftExecutor.FillOutcome.Fallback fallback -> {
                    // Couldn't hand off (recipe locked, grid uncleanable, …) — auto-craft below.
                }
            }
        }
        switch (CraftExecutor.craftOnce(player, job.currentStep().recipeId())) {
            case CraftExecutor.CraftResult.Success success -> {
                job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.FINISHED, success.crafted());
                    notify(player, Component.translatable("sprawlcrafting.craft.finished",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.GREEN));
                } else {
                    sync(player, job, CraftProgressPayload.State.CRAFTING, success.crafted());
                }
            }
            case CraftExecutor.CraftResult.MissingIngredient missing -> {
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                notify(player, Component.translatable("sprawlcrafting.craft.missing",
                        job.targetResult().getHoverName(), missing.missing()).withStyle(ChatFormatting.RED));
            }
            case CraftExecutor.CraftResult.RecipeGone gone -> {
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                notify(player, Component.translatable("sprawlcrafting.craft.recipe_gone",
                        job.targetResult().getHoverName()).withStyle(ChatFormatting.RED));
            }
        }
    }

    /**
     * Whether the player has a crafting grid open that the final craft can be handed off to.
     * The grid must be reported open by the client ({@link ClientCraftingView}), agree with the
     * server's view of the open menu ({@link GridContext#current}), and be large enough for the
     * step (a full-grid recipe needs a 3×3 table, not the 2×2 inventory grid).
     */
    private static boolean canHandOff(ServerPlayer player, CraftStep step) {
        GridContext open = ClientCraftingView.open(player);
        if (open == null || GridContext.current(player) != open) {
            return false;
        }
        return !step.needsFullGrid() || open == GridContext.CRAFTING_TABLE;
    }

    /** Player feedback line. 26.x renamed displayClientMessage -> sendSystemMessage(overlay). */
    private static void notify(ServerPlayer player, Component message) {
        //? if >=1.21.11 {
        /*player.sendSystemMessage(message, false);*/
        //?} else {
        player.displayClientMessage(message, false);
        //?}
    }

    /**
     * Vanilla send path — identical bytes to what both loaders' helpers produce. Guarded
     * by a channel check so a modless/vanilla client (the payload is registered optional)
     * is simply skipped rather than erroring on send.
     */
    private static void sync(ServerPlayer player, CraftJob job,
                             CraftProgressPayload.State state, ItemStack current) {
        if (!Services.PLATFORM.canReceive(player, CraftProgressPayload.TYPE.id())) {
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(new CraftProgressPayload(
                state, job.targetResult().copy(), current.copy(), job.craftsDone(), job.totalCrafts())));
    }
}
