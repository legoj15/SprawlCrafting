package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.legoj15.sprawlcrafting.platform.Services;
import com.legoj15.solver.CountFormat;

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

    /**
     * Cancels the player's running or paused deferred craft at their request (the "Cancel deferred
     * craft" keybind). Runs on the server thread between ticks, so it always lands between two whole
     * crafts — each craft (a crafting-station draw included) is applied all-or-nothing within
     * {@link #tick}, and everything already crafted stays in the player's inventory, the same end
     * state an involuntary cancel (missing ingredient, recipe gone) leaves behind.
     *
     * @return true if a job was cancelled; false when the player had none (a silent no-op)
     */
    public static boolean cancel(ServerPlayerEntity player) {
        CraftJob job = ACTIVE.remove(player.getUUID());
        if (job == null) {
            return false;
        }
        sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
        notify(player, new TranslationTextComponent("sprawlcrafting.craft.cancelled",
                job.targetResult().getHoverName(),
                CountFormat.format(job.craftsDone()),
                CountFormat.format(job.totalCrafts())).withStyle(TextFormatting.YELLOW));
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
     * {@link BurstBudget}) every tick until it is done. Each craft is validated against the live
     * inventory, exactly as a lone craft is: a paced craft one at a time through
     * {@link CraftExecutor#craftOnce}, a bulk burst in batches through
     * {@link CraftExecutor#craftBatch}, each batch an assignment provably equal to that many single
     * crafts (a single craft otherwise). A burst ends at a step boundary so the next step still
     * waits its half second — except into another bulk step, which continues on the next tick
     * (see {@link CraftJob#onCraftsPerformed(long)}). An interleaved job's steps run the same burst
     * loop round by round (see {@link CraftJob#ROUND_CRAFTS}).
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
        // A usable open crafting station (AE2 Crafting Terminal, which has its own 3×3 grid but is no
        // recipe-book menu) counts like a table in reach; the check is the open menu's class plus its live
        // power state, cheap enough for every burst tick, and asked only when the table check fails.
        if (job.currentStep().needsFullGrid()) {
            if (job.reachCheckDue()) {
                BlockPos table = CraftingTableReach.findInReach(player);
                if (table == null && !StationStorages.hasUsableStation(player)) {
                    job.holdForRetry();
                    sync(player, job, pausedState(job), ItemStack.EMPTY);
                    return;
                }
                job.markReachChecked(table);
            } else if (!CraftingTableReach.isStillInReach(player, job.reachTable())
                    && !StationStorages.hasUsableStation(player)) {
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
        // on death/respawn, which would wedge a window keyed on it.
        long start = System.nanoTime();
        long deadline = start + BURSTS.grant(player.getServer().getTickCount(), CraftQueueManager::bulkJobCount);
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
     * Performs the crafts due this tick: one for a paced step; for a bulk one, batches of as many
     * crafts as fit before {@code deadline} or the step boundary (there is no count cap: the time
     * budget bounds the tick, checked between batches; one batch is a single unit of work whose cost
     * is a fixed number of storage calls, not proportional to its crafts). Each batch is validated and applied by
     * {@link CraftExecutor#craftBatch} against the live inventory — one recipe lookup, slot scan and
     * station draw for many crafts, applying an assignment only while it provably equals that many
     * single crafts, and falling back to a single {@link CraftExecutor#craftOnce} otherwise. The
     * clock is checked after a batch, never before, so a job always makes at least one craft of
     * progress even when its {@code deadline} (nanoTime) has already passed.
     */
    private static void craftBurst(ServerPlayerEntity player, CraftJob job, boolean bulk, long deadline) {
        ItemStack lastCrafted = ItemStack.EMPTY;
        bursts:
        while (true) {
            // Final craft due with a compatible crafting grid open: lay the recipe out in that grid for
            // the player to grab (a real vanilla craft) instead of auto-crafting it.
            if (job.isFinalStep() && job.isLastCraftOfStep() && canHandOff(player, job.currentStep())) {
                CraftExecutor.FillOutcome fill = CraftExecutor.tryFillFinalGrid(player, job.currentStep().recipeId(),
                        job.produced(), job.currentCraftLayout(), job.usesProtected());
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
            CraftExecutor.CraftResult result = bulk
                    ? craftBulk(player, job)
                    : CraftExecutor.craftOnce(player, job.currentStep().recipeId(),
                            job.produced(), job.stationDependent(), job.currentCraftLayout(), false,
                            job.usesProtected());
            switch (result.kind()) {
                case SUCCESS:
                    if (result.usedStation()) {
                        // From now on this job needs the station (a job that started from its inventory alone
                        // but had to reach into the network because the player moved items): losing the
                        // station pauses it instead of cancelling.
                        job.setStationDependent(true);
                    }
                    lastCrafted = result.crafted();
                    boolean stepDone = job.onCraftsPerformed(result.crafts());
                    if (job.isFinished()) {
                        ACTIVE.remove(player.getUUID());
                        sync(player, job, CraftProgressPacket.State.FINISHED, lastCrafted);
                        notify(player, new TranslationTextComponent("sprawlcrafting.craft.finished",
                                job.targetResult().getHoverName()).withStyle(TextFormatting.GREEN));
                        return;
                    }
                    if (stepDone || !bulk) {
                        // A step boundary, or the one craft a paced step does per due tick: report it now
                        // (progress bar + step sound), exactly as every craft was reported before bursts. A boundary
                        // into another bulk step is the exception: batching finishes a bulk step in one tick and the
                        // next one starts on the next tick, so syncing every boundary would pop the step sound up to
                        // 20 times a second on a deep tree. It keeps the half-second cadence of a mid-step update.
                        if (!(stepDone && bulk && job.isBulkStep()) || job.progressSyncDue()) {
                            sync(player, job, CraftProgressPacket.State.CRAFTING, lastCrafted);
                        }
                        return;
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        break bursts; // time budget spent; the step stays due and continues next tick
                    }
                    continue;
                case MISSING_INGREDIENT:
                    // A job that relies on a station's storage (planned on it, or it already drew from it),
                    // whose station is closed or unusable now (the terminal is shut, or its network offline or
                    // out of power: StationStorages.open is null for both): the missing item may be sitting in
                    // that storage, so wait for the player to get the station back rather than cancelling.
                    // With a usable station open the storage IS visible, so a genuine shortfall (another player
                    // or automation drained it) still cancels. A job that never needed the station always
                    // takes the plain cancel.
                    if (job.stationDependent() && StationStorages.open(player) == null) {
                        job.holdForRetry();
                        sync(player, job, CraftProgressPacket.State.PAUSED_STATION, ItemStack.EMPTY);
                        return;
                    }
                    // An interleaved job's shortage may be only the rounds' rounding/ordering lag — an
                    // earlier step's slice ran floor-short of what this step's slice wants to consume —
                    // which a later round repays, so skip this step for the rest of the round and keep
                    // bursting (a round is never stuck; a drain pass that makes no progress reports the
                    // job stuck below and cancels like any shortfall).
                    if (job.deferCurrentStep()) {
                        if (job.isFinished()) {
                            ACTIVE.remove(player.getUUID());
                            sync(player, job, CraftProgressPacket.State.FINISHED, lastCrafted);
                            notify(player, new TranslationTextComponent("sprawlcrafting.craft.finished",
                                    job.targetResult().getHoverName()).withStyle(TextFormatting.GREEN));
                            return;
                        }
                        if (System.nanoTime() - deadline >= 0) {
                            break bursts; // time budget spent; the deferred step stays due next tick
                        }
                        continue;
                    }
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                    notify(player, new TranslationTextComponent("sprawlcrafting.craft.missing",
                            job.targetResult().getHoverName(), result.missing()).withStyle(TextFormatting.RED));
                    return;
                case NO_ROOM:
                    // A bulk step's craft had nowhere to put its output: continuing would drop every
                    // craft's result at the player's feet thousands of times per tick (the #35
                    // freeze), so the job stops for the player to make room instead of spilling.
                    ACTIVE.remove(player.getUUID());
                    sync(player, job, CraftProgressPacket.State.CANCELLED, ItemStack.EMPTY);
                    notify(player, new TranslationTextComponent("sprawlcrafting.craft.no_room",
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
        // A bulk burst spent its time budget mid-step; the step stays due and continues next
        // tick. Report progress at most once per half second so the toast (and its step sound) keeps its cadence.
        if (job.progressSyncDue()) {
            sync(player, job, CraftProgressPacket.State.CRAFTING, lastCrafted);
        }
    }

    /**
     * One batched craft call for a bulk step: as many crafts as the step has left (an interleaved
     * step's current round slice, {@link CraftJob#craftsLeftInStep()}), except on the
     * final step one fewer when that leaves at least 1, so the job's very last craft still goes
     * through the grid hand-off check one craft at a time (see the top of {@link #craftBurst}),
     * and never more than the current layout run still holds ({@link CraftJob#craftsLeftInLayoutRun()}):
     * the next run can change every cell's item, and one batch applies one assignment. A step with a
     * single craft left, batch or not, is a plain {@link CraftExecutor#craftOnce} —
     * {@link CraftExecutor#craftBatch} would only fall back to it anyway. {@code craftBatch} takes an
     * int, so a slice larger than that (a lagging step's backlog of rounds) is capped at
     * {@link Integer#MAX_VALUE}: the slice simply continues in the next batch. Both paths refuse a
     * craft whose output would spill ({@code refuseSpill}: a bulk step stops rather than drop at the
     * feet); a paced step keeps the spill, a few items at the feet being fine and expected there.
     */
    private static CraftExecutor.CraftResult craftBulk(ServerPlayerEntity player, CraftJob job) {
        long maxCrafts = job.craftsLeftInStep();
        if (job.isFinalStep() && maxCrafts > 1) {
            maxCrafts--;
        }
        maxCrafts = Math.min(maxCrafts, job.craftsLeftInLayoutRun());
        CraftExecutor.takeDropped(); // forget drops from anything before this call
        CraftExecutor.CraftResult result = maxCrafts <= 1
                ? CraftExecutor.craftOnce(player, job.currentStep().recipeId(),
                        job.produced(), job.stationDependent(), job.currentCraftLayout(), true, job.usesProtected())
                : CraftExecutor.craftBatch(player, job.currentStep().recipeId(),
                        job.produced(), job.stationDependent(), job.currentCraftLayout(),
                        (int) Math.min(maxCrafts, Integer.MAX_VALUE), true,
                        job.usesProtected());
        // Last line of defence: the room checks should leave nothing to drop, but if anything still
        // landed at the feet, stop the job here (as NO_ROOM) instead of repeating it every craft.
        ITextComponent dropped = CraftExecutor.takeDropped();
        if (dropped != null && result.kind() == CraftExecutor.CraftResult.Kind.SUCCESS) {
            return CraftExecutor.CraftResult.noRoom(dropped);
        }
        return result;
    }

    /**
     * Whether the player has a crafting grid open that the final craft can be handed off to: reported
     * open by the client ({@link ClientCraftingView}), agreeing with the server's view of the open menu
     * ({@link GridContext#current}), on screen, and large enough for the step. That includes a usable
     * crafting station's own grid (the AE2 Crafting Terminal's, a registered {@link CraftingGrids.Provider}
     * while the terminal is usable): the executor lays the recipe out through {@code Slot.set}, so no
     * vanilla slot layout is assumed.
     */
    private static boolean canHandOff(ServerPlayerEntity player, CraftStep step) {
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
     * Which pause to report for a full-grid step with no grid available: a job that relies on a station's
     * storage points the player back to the station ("open station": its grid and its items both live
     * there), otherwise the classic "need a crafting table", so a job that never touched the network never
     * shows station text.
     */
    private static CraftProgressPacket.State pausedState(CraftJob job) {
        return job.stationDependent() ? CraftProgressPacket.State.PAUSED_STATION : CraftProgressPacket.State.PAUSED;
    }

    private static void notify(ServerPlayerEntity player, ITextComponent message) {
        player.displayClientMessage(message, false);
    }

    /** Guarded by the platform's channel check, so a modless/vanilla client is simply skipped. */
    private static void sync(ServerPlayerEntity player, CraftJob job, CraftProgressPacket.State state, ItemStack current) {
        job.markSynced();
        // The state travels as an ordinal that an older client decodes with values()[i] and rejects when out
        // of range, so a state appended after the original five goes only to a client that has the
        // progress-states-v2 marker channel; any other gets the nearest original state.
        if (state == CraftProgressPacket.State.PAUSED_STATION && !Services.PLATFORM.canReceiveProgressStatesV2(player)) {
            state = CraftProgressPacket.State.PAUSED;
        }
        // done/total stay int on the wire (mod-1.0.3 layout); wireDone/wireTotal scale an over-int job down.
        Services.PLATFORM.sendProgress(player, new CraftProgressPacket(
                state, job.targetResult().copy(), current.copy(), job.wireDone(), job.wireTotal()));
    }
}
