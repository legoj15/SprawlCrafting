package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload;
import com.legoj15.sprawlcrafting.platform.Services;

import com.legoj15.solver.CountFormat;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    /** The burst time all players share within one server tick; see {@link BurstBudget}. */
    private static final BurstBudget BURSTS = new BurstBudget(
            CraftJob.SERVER_TICK_BURST_BUDGET_NANOS, CraftJob.BURST_BUDGET_NANOS);

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

    /**
     * Cancels the player's running or paused job at their request ({@code CancelCraftPayload},
     * the cancel key). Every payload handler runs on the server thread, and {@link #tick(ServerPlayer)}
     * is only called from the loaders' end-of-tick player loop, so a cancel always lands between two
     * whole crafts — no step-boundary logic is needed. The crafts already applied stay in the
     * inventory, as on any other terminal path.
     *
     * @return false if the player has no job to cancel.
     */
    public static boolean cancel(ServerPlayer player) {
        CraftJob job = ACTIVE.remove(player.getUUID());
        if (job == null) {
            return false;
        }
        sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
        notify(player, Component.translatable("sprawlcrafting.craft.cancelled",
                job.targetResult().getHoverName(),
                CountFormat.format(job.craftsDone()),
                CountFormat.format(job.totalCrafts())).withStyle(ChatFormatting.YELLOW));
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
     * {@link CraftJob#TICKS_PER_STEP} ticks; a bulk step performs a burst of crafts
     * (bounded only by time: {@link CraftJob#BURST_BUDGET_NANOS}, and within
     * its share of the {@link CraftJob#SERVER_TICK_BURST_BUDGET_NANOS} all players split, see
     * {@link BurstBudget}) every tick until it is done. Each craft is validated and applied by
     * {@link CraftExecutor#craftOnce} against the live inventory, exactly as a lone craft is. A burst ends
     * early at a step boundary, so the next step still waits its half second.
     */
    public static void tick(ServerPlayer player) {
        CraftJob job = ACTIVE.get(player.getUUID());
        if (job == null || !job.tick()) {
            return;
        }
        // 3×3 steps only run with a crafting table in reach. The full scan runs when due (every half
        // second, which is every craft of a paced step); a bulk step bursts every tick in between, so
        // those ticks re-validate just the table the last scan found (cheap) and pause the moment it
        // is gone or out of reach, instead of crafting on for up to half a second after the player left.
        // A player with a 3×3 crafting grid open needs no scan, and none of the re-checks: the open menu
        // IS the table, and it stays open only while the player may use it (vanilla closes a table's menu
        // past its stillValid reach, a little wider than the scan's; a crafting-upgrade menu is the player's own backpack or a storage in reach). It
        // is also how a modded grid with no crafting_table block counts at all.
        // A usable open crafting station (AE2 Crafting Terminal, which has its own 3×3 grid but is no
        // recipe-book menu) also counts like a table in reach; the check is the open menu's class plus its
        // live link status, cheap enough for every burst tick.
        if (job.currentStep().needsFullGrid() && !CraftingGrids.isFullGrid(CraftingGrids.find(player.containerMenu))) {
            if (job.reachCheckDue()) {
                BlockPos table = CraftingTableReach.findInReach(player);
                if (table == null && !hasOpenStation(player)) {
                    job.holdForRetry();
                    sync(player, job, pausedState(job), ItemStack.EMPTY);
                    return;
                }
                job.markReachChecked(table);
            } else if (!CraftingTableReach.isStillInReach(player, job.reachTable()) && !hasOpenStation(player)) {
                job.holdForRetry();
                sync(player, job, pausedState(job), ItemStack.EMPTY);
                return;
            }
        }
        if (!job.isBulkStep()) {
            // One craft per due tick, whatever the clock says: it never draws on the shared burst budget.
            craftBurst(player, job, false, 0L);
            return;
        }
        // The window is keyed on the SERVER tick, NOT player.tickCount: that is per-entity and resets to 0
        // on death/respawn (see DeferredCraftSync.maybeSync, which hit exactly that wedge).
        long start = System.nanoTime();
        long deadline = start + BURSTS.grant(player.level().getServer().getTickCount(),
                CraftQueueManager::bulkJobCount);
        try {
            craftBurst(player, job, true, deadline);
        } finally {
            BURSTS.charge(System.nanoTime() - start);
        }
    }

    /** Active jobs that burst this tick and so split its budget (see {@link CraftJob#burstsThisTick()}). */
    private static int bulkJobCount() {
        int count = 0;
        for (CraftJob job : ACTIVE.values()) {
            if (job.burstsThisTick()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Performs the crafts due this tick: one for a paced step; for a bulk one, as many as fit before
     * {@code deadline} or the step boundary (there is no count cap: the time budget alone bounds the
     * tick). The clock is checked after a craft, never before, so a job always makes at least one
     * craft of progress even when its {@code deadline} (nanoTime) has already passed.
     */
    private static void craftBurst(ServerPlayer player, CraftJob job, boolean bulk, long deadline) {
        ItemStack lastCrafted = ItemStack.EMPTY;
        while (true) {
            // If the final craft is due and the player has a compatible crafting grid open, lay the
            // recipe out in that grid for them to grab (a real vanilla craft) instead of auto-crafting
            // it. Any case that can't cleanly hand off falls through to the normal auto-craft below.
            if (job.isFinalStep() && job.isLastCraftOfStep() && canHandOff(player, job.currentStep())) {
                // instanceof chains rather than a pattern-matching switch: the 1.20.1 node targets Java 17.
                CraftExecutor.FillOutcome fill = CraftExecutor.tryFillFinalGrid(player, job.currentStep().recipeId(),
                        job.produced(), job.currentCraftLayout());
                if (fill instanceof CraftExecutor.FillOutcome.Filled filled) {
                    job.onCraftPerformed();
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.READY_IN_GRID, filled.result());
                    notify(player, Component.translatable("sprawlcrafting.craft.ready_in_grid",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.GREEN));
                    return;
                }
                if (fill instanceof CraftExecutor.FillOutcome.RecipeGone) {
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                    notify(player, Component.translatable("sprawlcrafting.craft.recipe_gone",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.RED));
                    return;
                }
                // FillOutcome.Fallback: couldn't hand off (recipe locked, grid uncleanable, …) — auto-craft below.
            }
            CraftExecutor.CraftResult craft = CraftExecutor.craftOnce(player, job.currentStep().recipeId(),
                    job.produced(), job.stationDependent(), job.currentCraftLayout());
            if (craft instanceof CraftExecutor.CraftResult.Success success) {
                if (success.usedStation()) {
                    // From now on this job needs the station (a job that started from its inventory alone
                    // but had to reach into the network because the player moved items): losing the
                    // station pauses it instead of cancelling.
                    job.setStationDependent(true);
                }
                lastCrafted = success.crafted();
                boolean stepDone = job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPayload.State.FINISHED, lastCrafted);
                    notify(player, Component.translatable("sprawlcrafting.craft.finished",
                            job.targetResult().getHoverName()).withStyle(ChatFormatting.GREEN));
                    return;
                }
                if (stepDone || !bulk) {
                    // A step boundary, or the one craft a paced step does per due tick: report it now
                    // (progress bar + step sound), exactly as every craft was reported before bursts.
                    sync(player, job, CraftProgressPayload.State.CRAFTING, lastCrafted);
                    return;
                }
                if (System.nanoTime() - deadline >= 0) {
                    break; // time budget spent; the step stays due and continues next tick
                }
                continue;
            }
            if (craft instanceof CraftExecutor.CraftResult.MissingIngredient missing) {
                // A job that relies on a station's storage (planned on it, or it already drew from it),
                // whose station is closed or unusable now (the terminal is shut, or its network offline or
                // out of power: StationStorages.open is null for both): the missing item may be sitting in
                // that storage, so wait for the player to get the station back rather than cancelling.
                // With a usable station open the storage IS visible, so a genuine shortfall (another player
                // or automation drained it) still cancels, as before. A job that never needed the station
                // always takes the plain cancel.
                if (job.stationDependent() && StationStorages.open(player) == null) {
                    job.holdForRetry();
                    sync(player, job, CraftProgressPayload.State.PAUSED_STATION, ItemStack.EMPTY);
                    return;
                }
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                notify(player, Component.translatable("sprawlcrafting.craft.missing",
                        job.targetResult().getHoverName(), missing.missing()).withStyle(ChatFormatting.RED));
            } else if (craft instanceof CraftExecutor.CraftResult.RecipeGone) {
                ACTIVE.remove(player.getUUID());
                sync(player, job, CraftProgressPayload.State.CANCELLED, ItemStack.EMPTY);
                notify(player, Component.translatable("sprawlcrafting.craft.recipe_gone",
                        job.targetResult().getHoverName()).withStyle(ChatFormatting.RED));
            }
            return;
        }
        // A bulk burst spent its time budget mid-step; the step stays due and continues next
        // tick. Report progress at most once per half second so the toast (and its step sound) keeps its cadence.
        if (job.progressSyncDue()) {
            sync(player, job, CraftProgressPayload.State.CRAFTING, lastCrafted);
        }
    }

    /**
     * Whether the player has a crafting grid open that the final craft can be handed off to.
     * The grid must be reported open by the client ({@link ClientCraftingView}), agree with the
     * server's view of the open menu ({@link GridContext#current}), be on screen
     * ({@link CraftingGrids.Grid#visible}: a crafting upgrade behind a closed tab is not), and be
     * large enough for the step (a full-grid recipe needs a 3×3 table, not the 2×2 inventory grid).
     * That includes a usable crafting station's own grid (the AE2 Crafting Terminal's, a registered
     * {@link CraftingGrids.Provider} while the terminal is usable): the executor lays the recipe out
     * through {@code Slot.set}, so no vanilla slot layout is assumed.
     */
    private static boolean canHandOff(ServerPlayer player, CraftStep step) {
        GridContext open = ClientCraftingView.open(player);
        if (open == null || GridContext.current(player) != open) {
            return false;
        }
        CraftingGrids.Grid grid = CraftingGrids.find(player.containerMenu);
        if (grid == null || !grid.visible()) {
            return false;
        }
        return !step.needsFullGrid() || open == GridContext.CRAFTING_TABLE;
    }

    /**
     * Whether the player has a usable crafting station open (it brings its own 3×3 grid). Usable, not merely
     * open: an AE2 terminal whose network is offline does not stand in for a crafting table, so a full-grid
     * step waits (consistent with {@link GridContext#current}, which then plans it as the 2×2 grid).
     */
    private static boolean hasOpenStation(ServerPlayer player) {
        return StationStorages.hasUsableStation(player);
    }

    /**
     * Which pause to report for a full-grid step with no grid available: a job that relies on a station's
     * storage points the player back to the station ("open station": its grid and its items both live
     * there), otherwise the classic "need a crafting table" (so a job that never touched the network never
     * shows station text). (1.12.2's {@code pausedState}.)
     */
    private static CraftProgressPayload.State pausedState(CraftJob job) {
        return job.stationDependent()
                ? CraftProgressPayload.State.PAUSED_STATION
                : CraftProgressPayload.State.PAUSED;
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
     * Streams the snapshot through the platform helper (the vanilla payload packet on 1.20.5+, the
     * loader's channel API before; Forge always goes through its own channel because its patched codec
     * cannot encode a raw payload packet). Guarded by a channel check so a modless/vanilla client (the
     * payload is registered optional) is simply skipped rather than erroring on send.
     */
    private static void sync(ServerPlayer player, CraftJob job,
                             CraftProgressPayload.State state, ItemStack current) {
        job.markSynced();
        if (!Services.PLATFORM.canReceive(player, CraftProgressPayload.ID)) {
            return;
        }
        // The state travels as an ordinal that an older client decodes with values()[i], so an ordinal
        // it does not know would throw. Send the states appended after the original five only to a client
        // that registered ProgressStatesV2Payload; any other gets the nearest original state.
        if (state == CraftProgressPayload.State.PAUSED_STATION
                && !Services.PLATFORM.canReceive(player, ProgressStatesV2Payload.ID)) {
            state = CraftProgressPayload.State.PAUSED;
        }
        // The progress fields stay int on the wire (mod-1.0.3 layout); wireDone/wireTotal scale a job
        // of more than Integer.MAX_VALUE crafts down proportionally rather than wrapping.
        Services.PLATFORM.sendToPlayer(player, new CraftProgressPayload(
                state, job.targetResult().copy(), current.copy(), job.wireDone(), job.wireTotal()));
    }
}
