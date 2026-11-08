package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * Server-side registry of each player's active craft job. One job per player: enqueueing while one is
 * running is rejected. Loader entry points wire {@link #tick} into their end-of-server-tick player loop,
 * {@link #clear} into disconnect handling, and {@link #clearAll} into server shutdown. Progress streams
 * to the client's toast via {@link CraftProgressPacket}; terminal events also get a chat line.
 */
public final class CraftQueueManager {

    private static final Map<UUID, CraftJob> ACTIVE = new ConcurrentHashMap<>();

    private CraftQueueManager() {
    }

    public static Optional<CraftJob> activeJob(UUID playerId) {
        return Optional.ofNullable(ACTIVE.get(playerId));
    }

    /** @return false if the player already has a job running (single-job rule). */
    public static boolean start(ServerPlayerEntity player, CraftJob job) {
        if (ACTIVE.putIfAbsent(player.getUUID(), job) != null) {
            return false;
        }
        sync(player, job, CraftProgressPacket.State.CRAFTING, ItemStack.EMPTY);
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
    public static void tick(ServerPlayerEntity player) {
        CraftJob job = ACTIVE.get(player.getUUID());
        if (job == null || !job.tick()) {
            return;
        }
        // 3×3 steps only run with a crafting table in reach; re-check every half second.
        if (job.currentStep().needsFullGrid() && !CraftingTableReach.isInReach(player)) {
            job.holdForRetry();
            sync(player, job, CraftProgressPacket.State.PAUSED, ItemStack.EMPTY);
            return;
        }
        // Final craft due with a compatible crafting grid open: lay the recipe out in that grid for the
        // player to grab (a real vanilla craft) instead of auto-crafting it.
        if (job.isFinalStep() && job.isLastCraftOfStep() && canHandOff(player, job.currentStep())) {
            CraftExecutor.FillOutcome fill = CraftExecutor.tryFillFinalGrid(player, job.currentStep().recipeId());
            if (fill.kind() == CraftExecutor.FillOutcome.Kind.FILLED) {
                job.onCraftPerformed();
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPacket.State.READY_IN_GRID, fill.result());
                notify(player, new TranslationTextComponent("sprawlcrafting.craft.ready_in_grid",
                        job.targetResult().getHoverName()).withStyle(TextFormatting.GREEN));
                return;
            }
            if (fill.kind() == CraftExecutor.FillOutcome.Kind.RECIPE_GONE) {
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                notify(player, new TranslationTextComponent("sprawlcrafting.craft.recipe_gone",
                        job.targetResult().getHoverName()).withStyle(TextFormatting.RED));
                return;
            }
            // FALLBACK: couldn't hand off (recipe locked, grid uncleanable, …) — auto-craft below.
        }
        CraftExecutor.CraftResult result = CraftExecutor.craftOnce(player, job.currentStep().recipeId());
        switch (result.kind()) {
            case SUCCESS:
                job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPacket.State.FINISHED, result.crafted());
                    notify(player, new TranslationTextComponent("sprawlcrafting.craft.finished",
                            job.targetResult().getHoverName()).withStyle(TextFormatting.GREEN));
                } else {
                    sync(player, job, CraftProgressPacket.State.CRAFTING, result.crafted());
                }
                break;
            case MISSING_INGREDIENT:
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                notify(player, new TranslationTextComponent("sprawlcrafting.craft.missing",
                        job.targetResult().getHoverName(), result.missing()).withStyle(TextFormatting.RED));
                break;
            case RECIPE_GONE:
            default:
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                notify(player, new TranslationTextComponent("sprawlcrafting.craft.recipe_gone",
                        job.targetResult().getHoverName()).withStyle(TextFormatting.RED));
                break;
        }
    }

    /**
     * Whether the player has a crafting grid open that the final craft can be handed off to: reported
     * open by the client ({@link ClientCraftingView}), agreeing with the server's view of the open menu
     * ({@link GridContext#current}), and large enough for the step.
     */
    private static boolean canHandOff(ServerPlayerEntity player, CraftStep step) {
        GridContext open = ClientCraftingView.open(player);
        if (open == null || GridContext.current(player) != open) {
            return false;
        }
        return !step.needsFullGrid() || open == GridContext.CRAFTING_TABLE;
    }

    private static void notify(ServerPlayerEntity player, ITextComponent message) {
        player.displayClientMessage(message, false);
    }

    /** Guarded by the platform's channel check, so a modless/vanilla client is simply skipped. */
    private static void sync(ServerPlayerEntity player, CraftJob job, CraftProgressPacket.State state, ItemStack current) {
        Services.PLATFORM.sendProgress(player, new CraftProgressPacket(
                state, job.targetResult().copy(), current.copy(), job.craftsDone(), job.totalCrafts()));
    }
}
