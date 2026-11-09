package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
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

    /** The burst time all players share within one server tick; see {@link BurstBudget}. */
    private static final BurstBudget BURSTS = new BurstBudget(
            CraftJob.SERVER_TICK_BURST_BUDGET_NANOS, CraftJob.BURST_BUDGET_NANOS);

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
        BURSTS.reset();
        ClientCraftingView.clearAll();
    }

    /**
     * Advances the player's job by one tick. When a craft is due: a paced step ({@code <=}
     * {@link CraftJob#BULK_THRESHOLD} crafts) performs exactly one and syncs progress, once per
     * {@link CraftJob#TICKS_PER_STEP} ticks; a bulk step performs a burst of up to
     * {@link CraftJob#MAX_CRAFTS_PER_TICK} crafts (within {@link CraftJob#BURST_BUDGET_NANOS}, and within
     * its share of the {@link CraftJob#SERVER_TICK_BURST_BUDGET_NANOS} all players split, see
     * {@link BurstBudget}) every tick until it is done. Each craft is validated and applied by
     * {@link CraftExecutor#craftOnce} against the live inventory, exactly as a lone craft is. A burst ends
     * at a step boundary so the next step still waits its half second.
     */
    public static void tick(ServerPlayerEntity player) {
        CraftJob job = ACTIVE.get(player.getUUID());
        if (job == null || !job.tick()) {
            return;
        }
        // 3×3 steps only run with a crafting table in reach. The full scan runs when due (every half
        // second, which is every craft of a paced step); a bulk step bursts every tick in between, so
        // those ticks re-validate just the table the last scan found (cheap) and pause the moment it
        // is gone or out of reach, instead of crafting on for up to half a second after the player left.
        if (job.currentStep().needsFullGrid()) {
            if (job.reachCheckDue()) {
                BlockPos table = CraftingTableReach.findInReach(player);
                if (table == null) {
                    job.holdForRetry();
                    sync(player, job, CraftProgressPacket.State.PAUSED, ItemStack.EMPTY);
                    return;
                }
                job.markReachChecked(table);
            } else if (!CraftingTableReach.isStillInReach(player, job.reachTable())) {
                job.holdForRetry();
                sync(player, job, CraftProgressPacket.State.PAUSED, ItemStack.EMPTY);
                return;
            }
        }
        if (!job.isBulkStep()) {
            // One craft per due tick, whatever the clock says: it never draws on the shared burst budget.
            craftBurst(player, job, false, 0L);
            return;
        }
        // The window is keyed on the SERVER tick, NOT player.tickCount: that is per-entity and resets to 0
        // on death/respawn, which would wedge a window keyed on it.
        long start = System.nanoTime();
        long deadline = start + BURSTS.grant(player.getServer().getTickCount(), CraftQueueManager::bulkJobCount);
        try {
            craftBurst(player, job, true, deadline);
        } finally {
            BURSTS.charge(System.nanoTime() - start);
        }
    }

    /** Active jobs currently in a bulk step: the players that may burst this tick and so split its budget. */
    private static int bulkJobCount() {
        int count = 0;
        for (CraftJob job : ACTIVE.values()) {
            if (!job.isFinished() && job.isBulkStep()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Performs the crafts due this tick: one for a paced step, up to {@link CraftJob#MAX_CRAFTS_PER_TICK}
     * for a bulk one. The clock is checked after a craft, never before, so a job always makes at least one
     * craft of progress even when its {@code deadline} (nanoTime) has already passed.
     */
    private static void craftBurst(ServerPlayerEntity player, CraftJob job, boolean bulk, long deadline) {
        int cap = bulk ? CraftJob.MAX_CRAFTS_PER_TICK : 1;
        ItemStack lastCrafted = ItemStack.EMPTY;
        bursts:
        for (int burst = 0; burst < cap; burst++) {
            // Final craft due with a compatible crafting grid open: lay the recipe out in that grid for
            // the player to grab (a real vanilla craft) instead of auto-crafting it.
            if (job.isFinalStep() && job.isLastCraftOfStep() && canHandOff(player, job.currentStep())) {
                CraftExecutor.FillOutcome fill = CraftExecutor.tryFillFinalGrid(player, job.currentStep().recipeId(),
                        job.produced());
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
            CraftExecutor.CraftResult result = CraftExecutor.craftOnce(player, job.currentStep().recipeId(), job.produced());
            switch (result.kind()) {
                case SUCCESS:
                    lastCrafted = result.crafted();
                    boolean stepDone = job.onCraftPerformed();
                    if (job.isFinished()) {
                        ACTIVE.remove(player.getUUID());
                        sync(player, job, CraftProgressPacket.State.FINISHED, lastCrafted);
                        notify(player, new TranslationTextComponent("sprawlcrafting.craft.finished",
                                job.targetResult().getHoverName()).withStyle(TextFormatting.GREEN));
                        return;
                    }
                    if (stepDone || !bulk) {
                        // A step boundary, or the one craft a paced step does per due tick: report it now
                        // (progress bar + step sound), exactly as every craft was reported before bursts.
                        sync(player, job, CraftProgressPacket.State.CRAFTING, lastCrafted);
                        return;
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        break bursts; // time budget spent; the step stays due and continues next tick
                    }
                    continue;
                case MISSING_INGREDIENT:
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                    notify(player, new TranslationTextComponent("sprawlcrafting.craft.missing",
                            job.targetResult().getHoverName(), result.missing()).withStyle(TextFormatting.RED));
                    return;
                case RECIPE_GONE:
                default:
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                    notify(player, new TranslationTextComponent("sprawlcrafting.craft.recipe_gone",
                            job.targetResult().getHoverName()).withStyle(TextFormatting.RED));
                    return;
            }
        }
        // A bulk burst hit its per-tick cap (count or time) mid-step; the step stays due and continues next
        // tick. Report progress at most once per half second so the toast (and its step sound) keeps its cadence.
        if (job.progressSyncDue()) {
            sync(player, job, CraftProgressPacket.State.CRAFTING, lastCrafted);
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
        job.markSynced();
        // done/total stay int on the wire (mod-1.0.3 layout); wireDone/wireTotal scale an over-int job down.
        Services.PLATFORM.sendProgress(player, new CraftProgressPacket(
                state, job.targetResult().copy(), current.copy(), job.wireDone(), job.wireTotal()));
    }
}
