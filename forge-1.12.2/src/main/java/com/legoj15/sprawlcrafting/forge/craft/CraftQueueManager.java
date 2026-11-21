package com.legoj15.sprawlcrafting.forge.craft;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

import org.apache.logging.log4j.LogManager;

import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;
import com.legoj15.solver.CountFormat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * Server-side registry of each player's active craft job. v1 allows a single job per player:
 * enqueueing while one is running is rejected. Java-8 port of the modern queue manager, including
 * the final-grid hand-off: the last craft of a job is laid into the player's open crafting grid
 * (via {@link ServerGridPlacer}) when one is open, auto-crafted into the inventory otherwise.
 *
 * <p>The mod entry point wires {@link #tick(EntityPlayerMP)} into the per-player server tick,
 * {@link #clear(UUID)} into logout, and {@link #clearAll()} into server stop.
 */
public final class CraftQueueManager {

    private static final Map<UUID, CraftJob> ACTIVE = new ConcurrentHashMap<UUID, CraftJob>();

    /** The burst time all players share within one server tick; see {@link BurstBudget}. */
    private static final BurstBudget BURSTS = new BurstBudget(
            CraftJob.SERVER_TICK_BURST_BUDGET_NANOS, CraftJob.BURST_BUDGET_NANOS);

    private CraftQueueManager() {
    }

    public static Optional<CraftJob> activeJob(UUID playerId) {
        return Optional.ofNullable(ACTIVE.get(playerId));
    }

    /** @return false if the player already has a job running (single-job rule). */
    public static boolean start(EntityPlayerMP player, CraftJob job) {
        if (ACTIVE.putIfAbsent(player.getUniqueID(), job) != null) {
            return false;
        }
        // Every job starts from the safe "no crafting screen" default: a screen state left over
        // from a previous job (the watcher goes silent on terminal states) must never satisfy the
        // hand-off gate — the client re-reports from scratch once it sees this job's first sync.
        ClientCraftingView.clearScreen(player.getUniqueID());
        sync(player, job, CraftProgressMessage.State.CRAFTING, ItemStack.EMPTY);
        return true;
    }

    /**
     * Player-facing cancel (issue #31): drops the player's active job and reports it like the
     * engine's own failure paths (shortfall, recipe gone). Runs on the server thread between
     * ticks (the packet handler hops there), so it always lands between two whole crafts — each
     * craft, station / chest draws included, is all-or-nothing inside
     * {@link CraftExecutor#craftOnce} — and needs no step-boundary logic: the crafts that already
     * landed simply stay crafted.
     *
     * @return true if a job was running and is now cancelled; false when there was none (no-op).
     */
    public static boolean cancel(EntityPlayerMP player) {
        CraftJob job = ACTIVE.remove(player.getUniqueID());
        if (job == null) {
            return false;
        }
        sync(player, job, CraftProgressMessage.State.CANCELLED, ItemStack.EMPTY);
        notify(player, message("sprawlcrafting.craft.cancelled", TextFormatting.YELLOW,
                job.targetResult().getDisplayName(),
                CountFormat.format(job.craftsDone()),
                CountFormat.format(job.totalCrafts())));
        return true;
    }

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static void clearAll() {
        ACTIVE.clear();
        BURSTS.reset();
    }

    /**
     * Advances the player's job by one tick. When a craft is due: a paced step ({@code <=}
     * {@link CraftJob#BULK_THRESHOLD} crafts) performs exactly one and syncs progress, once per
     * {@link CraftJob#TICKS_PER_STEP} ticks; a bulk step performs a burst of crafts
     * (bounded only by time: {@link CraftJob#BURST_BUDGET_NANOS}, and within
     * its share of the {@link CraftJob#SERVER_TICK_BURST_BUDGET_NANOS} all players split, see
     * {@link BurstBudget}) every tick until it is done. Each craft is validated and applied by
     * {@link CraftExecutor#craftOnce} against the live inventory, exactly as a lone craft is. A burst ends
     * at a step boundary so the next step still waits its half second. An interleaved job runs the
     * same burst loop batch by batch across step boundaries (see {@link CraftJob#isInterleaved()}).
     */
    public static void tick(EntityPlayerMP player) {
        CraftJob job = ACTIVE.get(player.getUniqueID());
        if (job == null || !job.tick()) {
            return;
        }
        // 3x3 steps only run with full-grid access (a vanilla table in reach, or a 3x3 crafter open). The
        // full check runs when due (every half second, which is every craft of a paced step); a bulk step
        // bursts every tick in between, so those ticks re-validate just what the last check found (cheap)
        // and pause the moment it is gone, instead of crafting on for up to half a second after the
        // player left.
        if (job.currentStep().needsFullGrid()) {
            if (job.reachCheckDue()) {
                BlockPos table = CraftingTableReach.findInReach(player);
                if (table == null && !hasOpenCrafter(player)) {
                    job.holdForRetry();
                    sync(player, job, pausedState(job), ItemStack.EMPTY);
                    return;
                }
                job.markReachChecked(table);
            } else if (!CraftingTableReach.isStillInReach(player, job.reachTable()) && !hasOpenCrafter(player)) {
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
        // The window is keyed on the SERVER tick, NOT player.ticksExisted: that is per-entity and resets to 0
        // on death/respawn, which would wedge a window keyed on it. This hook runs once per player per
        // server tick, so every player of a tick shares one tickCounter value and so one window.
        long start = System.nanoTime();
        long deadline = start + BURSTS.grant(player.getServer().getTickCounter(), new IntSupplier() {
            @Override
            public int getAsInt() {
                return bulkJobCount();
            }
        });
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
     * craft of progress even when its {@code deadline} (nanoTime) has already passed. An interleaved
     * job's crafts each re-derive the schedule's next batch, so one burst spans batches and steps
     * alike; its crafts also pass the no-room gate below, which a sequential job's never do (they
     * keep the spill-at-the-feet rule this tree has always had).
     */
    private static void craftBurst(EntityPlayerMP player, CraftJob job, boolean bulk, long deadline) {
        ItemStack lastCrafted = ItemStack.EMPTY;
        while (true) {
            // Final-step hand-off: if the very last craft is due and the player is looking at a
            // compatible grid, lay its ingredients there instead — a real, takeable vanilla craft —
            // and finish the job optimistically (grabbing is its own confirmation). Any doubt falls
            // through to the auto-craft below, which remains the only place the item is granted.
            IRecipe handOff = handOffCandidate(player, job);
            if (handOff != null
                    && ServerGridPlacer.placeFinalCraft(player, handOff, job.produced(), job.currentCraftLayout(),
                            job.usesProtected()) == ServerGridPlacer.HandOffResult.FILLED) {
                ACTIVE.remove(player.getUniqueID());
                job.onCraftPerformed();
                sync(player, job, CraftProgressMessage.State.READY_IN_GRID, job.targetResult());
                notify(player, message("sprawlcrafting.craft.ready_in_grid", TextFormatting.GREEN,
                        job.targetResult().getDisplayName()));
                return;
            }
            // The interleaved path's no-room gate, before anything moves: a craft whose output has
            // nowhere to go would drop at the player's feet every craft of the burst, so it is
            // refused up front and the schedule shrinks (see below) instead of spilling.
            if (bulk && job.isInterleaved() && !hasRoomFor(player, job.currentStep())) {
                // The batch's output had nowhere to go: shrink the schedule's batch cap and let it
                // re-derive the next batch from the model — a smaller batch fits a tighter network,
                // and a network only full of intermediates is drained by the consuming steps the
                // re-derivation picks next. A batch already at the per-craft floor cannot shrink:
                // that storage is genuinely full.
                if (job.shrinkForRoom()) {
                    if (System.nanoTime() - deadline >= 0) {
                        break; // time budget spent; the smaller batch is due next tick
                    }
                    continue;
                }
                // No branch the target needs can run: continuing would drop every craft's result at
                // the player's feet thousands of times per tick, so the job stops for the player to
                // make room instead of spilling.
                ItemStack output = stepOutput(job.currentStep());
                logNoRoom(player, job, output);
                ACTIVE.remove(player.getUniqueID());
                sync(player, job, CraftProgressMessage.State.CANCELLED, ItemStack.EMPTY);
                notify(player, message("sprawlcrafting.craft.no_room", TextFormatting.RED,
                        job.targetResult().getDisplayName(),
                        output.isEmpty() ? job.currentStep().recipeId().toString() : output.getDisplayName()));
                return;
            }
            CraftExecutor.CraftResult result = CraftExecutor.craftOnce(player, job.currentStep().recipeId(),
                    job.produced(), job.currentCraftLayout(), job.usesProtected());
            if (result instanceof CraftExecutor.CraftResult.Success) {
                CraftExecutor.CraftResult.Success success = (CraftExecutor.CraftResult.Success) result;
                // Dependence can be learned mid-job: a craft that actually drew from the chest or the
                // network makes the job depend on it, whatever was visible when it started.
                if (success.usedChest()) {
                    job.setChestDependent(true);
                }
                if (success.usedNetwork()) {
                    job.setNetworkDependent(true);
                }
                lastCrafted = success.crafted();
                boolean stepDone = job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUniqueID());
                    sync(player, job, CraftProgressMessage.State.FINISHED, lastCrafted);
                    notify(player, message("sprawlcrafting.craft.finished", TextFormatting.GREEN,
                            job.targetResult().getDisplayName()));
                    return;
                }
                // An interleaved job's craft boundaries are not step boundaries: the schedule
                // interleaves the steps, so ending the burst at each one would cap the job at one
                // craft per tick. Its bursts run to the time budget across crafts and report on the
                // half-second cadence.
                if ((stepDone && !job.isInterleaved()) || !bulk) {
                    // A step boundary, or the one craft a paced step does per due tick: report it now
                    // (progress bar + step sound), exactly as every craft was reported before bursts.
                    sync(player, job, CraftProgressMessage.State.CRAFTING, lastCrafted);
                    return;
                }
                if (System.nanoTime() - deadline >= 0) {
                    break; // time budget spent; the step stays due and continues next tick
                }
                continue;
            }
            if (result instanceof CraftExecutor.CraftResult.MissingIngredient) {
                // A chest-dependent job whose station is currently closed can't see the connected
                // inventory; the missing item may be sitting in it, so wait for the player to reopen
                // the station rather than cancelling. (With the station open the chest IS visible, so a
                // genuine shortfall — e.g. a hopper drained it — still cancels, as before.) A network-
                // dependent job follows the same rule for its network crafting terminal (AE2): with the
                // terminal closed or unpowered the network is invisible, so wait; open and usable, the
                // network was a source, so a miss is genuine and cancels. Each source is judged on its
                // own: a job planned at a chest does not stop waiting because some terminal is open.
                if ((job.chestDependent() && !ExternalSlots.present(player))
                        || (job.networkDependent() && !NetworkTerminals.usable(player))) {
                    job.holdForRetry();
                    sync(player, job, CraftProgressMessage.State.PAUSED_STATION, ItemStack.EMPTY);
                    return;
                }
                // An interleaved job's schedule model already ran every producer this step needed
                // ahead of it, so a shortage the model did not foresee is real — the player spent,
                // moved or lost the material — and no reordering can recover it: cancel like any job.
                ACTIVE.remove(player.getUniqueID());
                sync(player, job, CraftProgressMessage.State.CANCELLED, ItemStack.EMPTY);
                notify(player, message("sprawlcrafting.craft.missing", TextFormatting.RED,
                        job.targetResult().getDisplayName(),
                        ((CraftExecutor.CraftResult.MissingIngredient) result).missing()));
            } else {
                ACTIVE.remove(player.getUniqueID());
                sync(player, job, CraftProgressMessage.State.CANCELLED, ItemStack.EMPTY);
                notify(player, message("sprawlcrafting.craft.recipe_gone", TextFormatting.RED,
                        job.targetResult().getDisplayName()));
            }
            return;
        }
        // A bulk burst spent its time budget mid-step; the step stays due and continues next
        // tick. Report progress at most once per half second so the toast (and its step sound) keeps its cadence.
        if (job.progressSyncDue()) {
            sync(player, job, CraftProgressMessage.State.CRAFTING, lastCrafted);
        }
    }

    /**
     * Whether one craft of {@code step} still has somewhere to put its output: the 36 main slots
     * ({@link CraftExecutor#fitsInMainInventory}'s rule — merge room, then empty slots; slots the
     * craft's own consumption would free are not counted, the executor's assignment is not visible
     * here) or, with a usable network terminal open, its network when a simulated insert says it
     * takes the whole output. Asked only of an interleaved job's crafts: the refusal is what lets
     * the schedule shrink its batch around a full store instead of spilling the output at the
     * player's feet.
     */
    private static boolean hasRoomFor(EntityPlayerMP player, CraftStep step) {
        ItemStack output = stepOutput(step);
        if (output.isEmpty() || CraftExecutor.fitsInMainInventory(player.inventory,
                Collections.singletonList(output))) {
            return true;
        }
        if (!NetworkTerminals.usable(player)) {
            return false;
        }
        NetworkTerminals.View network = NetworkTerminals.view(player);
        return network != null && network.accepts(output);
    }

    /**
     * One craft's declared output — the recipe's own stack (NBT intact) at the plan's
     * {@link CraftStep#resultCount()} — or {@link ItemStack#EMPTY} when the step's recipe or result
     * is no longer known (the craft itself then reports {@code RecipeGone}).
     */
    private static ItemStack stepOutput(CraftStep step) {
        if (step.result() == null || step.resultCount() < 1) {
            return ItemStack.EMPTY;
        }
        IRecipe recipe = CraftingManager.getRecipe(step.recipeId());
        ItemStack output = recipe == null ? ItemStack.EMPTY : recipe.getRecipeOutput().copy();
        if (output.isEmpty()) {
            return ItemStack.EMPTY;
        }
        output.setCount(Math.max(1, step.resultCount()));
        return output;
    }

    /**
     * The log line behind a no-room stop: what the step was making, whether a network terminal is
     * open to take the overflow, and how many of the 36 main slots are free — enough to tell a full
     * store from one that refuses — plus the schedule's own view of every step.
     */
    private static void logNoRoom(EntityPlayerMP player, CraftJob job, ItemStack output) {
        int free = 0;
        for (int i = 0; i < CraftPlanner.MAIN_INVENTORY_SIZE; i++) {
            if (player.inventory.mainInventory.get(i).isEmpty()) {
                free++;
            }
        }
        LogManager.getLogger("SprawlCrafting").warn("SprawlCrafting: no room for {} (terminal open: {});"
                        + " free main slots {}; schedule:{}", output.isEmpty() ? "?" : output.getDisplayName(),
                Boolean.valueOf(NetworkTerminals.isOpen(player)), Integer.valueOf(free), job.scheduleReport());
    }

    /**
     * The final step's recipe, iff its very last craft is due AND the player is looking at a grid
     * it fits: a 3x3 crafter open right now (server-visible via the open container), or — for a
     * recipe that fits 2x2 — the inventory screen, which only the client can see
     * ({@link ClientCraftingView}, fed by the screen-state message). Null = no hand-off; auto-craft.
     */
    private static IRecipe handOffCandidate(EntityPlayerMP player, CraftJob job) {
        if (!job.isFinalStep() || !job.isLastCraftOfStep()) {
            return null;
        }
        // A pre-v2 client can't display READY_IN_GRID (its enum decoder clamps the unknown
        // ordinal), so it keeps the pre-v2 behavior: every craft auto-crafts into the inventory.
        if (!ClientCraftingView.capable(player.getUniqueID())) {
            return null;
        }
        // A network crafting terminal (AE2) never gets the hand-off: its grid is AE2's item handler,
        // not an InventoryCrafting the placer can verify, and AE2 refills it from the network as the
        // result is taken. The final craft completes into the inventory instead.
        if (NetworkTerminals.isOpen(player)) {
            return null;
        }
        IRecipe recipe = CraftingManager.getRecipe(job.currentStep().recipeId());
        if (recipe == null) {
            return null;
        }
        if (GridContext.current(player) == GridContext.CRAFTING_TABLE) {
            return recipe;
        }
        if (player.openContainer == player.inventoryContainer
                && recipe.canFit(2, 2)
                && ClientCraftingView.inventoryScreenOpen(player.getUniqueID())) {
            return recipe;
        }
        return null;
    }

    /**
     * Whether a 3x3 crafter is open this instant. Alongside a vanilla table in reach this makes a 3x3
     * step runnable: it covers a Tinkers' Construct Crafting Station, which {@link CraftingTableReach}
     * (it scans only for the vanilla table block) cannot see, so a chain started at a station no longer
     * wedges forever on a base with no vanilla table. An open AE2 Crafting Terminal counts too (a cable
     * part, not a table block; {@link GridContext#current}) — but only while its network is usable: an
     * unpowered terminal is no crafter for a 3x3 step, the same station semantics as its pause gate.
     * Cheap (a walk of the open container's slots), so the burst ticks between table scans use it as-is.
     */
    private static boolean hasOpenCrafter(EntityPlayerMP player) {
        return GridContext.current(player) == GridContext.CRAFTING_TABLE;
    }

    /**
     * Which pause to report: a job that depends on a station (a Crafting Station's chest or a network
     * terminal's network) points the player back to it ("open station" — its grid and its material
     * both live there), otherwise the classic "need table". Without this a station-only base, where
     * the resolution is to reopen the station rather than place a vanilla table, would show a
     * misleading hint and look wedged.
     */
    private static CraftProgressMessage.State pausedState(CraftJob job) {
        return job.stationDependent()
                ? CraftProgressMessage.State.PAUSED_STATION
                : CraftProgressMessage.State.PAUSED;
    }

    private static void notify(EntityPlayerMP player, ITextComponent message) {
        player.sendStatusMessage(message, false);
    }

    private static ITextComponent message(String key, TextFormatting color, Object... args) {
        TextComponentTranslation component = new TextComponentTranslation(key, args);
        component.getStyle().setColor(color);
        return component;
    }

    private static void sync(EntityPlayerMP player, CraftJob job,
                             CraftProgressMessage.State state, ItemStack current) {
        job.markSynced();
        // done/total stay int on the frozen main channel; wireDone/wireTotal scale an over-int job down.
        SprawlNetwork.sendProgress(player, new CraftProgressMessage(
                state, job.targetResult().copy(), current.copy(), job.wireDone(), job.wireTotal()));
    }
}
