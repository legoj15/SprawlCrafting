package com.legoj15.sprawlcrafting.forge.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

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

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /**
     * Advances the player's job by one tick. When a craft is due: a paced step ({@code <=}
     * {@link CraftJob#BULK_THRESHOLD} crafts) performs exactly one and syncs progress, once per
     * {@link CraftJob#TICKS_PER_STEP} ticks; a bulk step performs a burst of up to
     * {@link CraftJob#MAX_CRAFTS_PER_TICK} crafts (within {@link CraftJob#BURST_BUDGET_NANOS}) every tick
     * until it is done. Each craft is validated and applied by {@link CraftExecutor#craftOnce} against the
     * live inventory, exactly as a lone craft is. A burst ends at a step boundary so the next step still
     * waits its half second.
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
        boolean bulk = job.isBulkStep();
        int cap = bulk ? CraftJob.MAX_CRAFTS_PER_TICK : 1;
        ItemStack lastCrafted = ItemStack.EMPTY;
        long deadline = System.nanoTime() + CraftJob.BURST_BUDGET_NANOS;
        for (int burst = 0; burst < cap; burst++) {
            // Final-step hand-off: if the very last craft is due and the player is looking at a
            // compatible grid, lay its ingredients there instead — a real, takeable vanilla craft —
            // and finish the job optimistically (grabbing is its own confirmation). Any doubt falls
            // through to the auto-craft below, which remains the only place the item is granted.
            IRecipe handOff = handOffCandidate(player, job);
            if (handOff != null
                    && ServerGridPlacer.placeFinalCraft(player, handOff) == ServerGridPlacer.HandOffResult.FILLED) {
                ACTIVE.remove(player.getUniqueID());
                job.onCraftPerformed();
                sync(player, job, CraftProgressMessage.State.READY_IN_GRID, job.targetResult());
                notify(player, message("sprawlcrafting.craft.ready_in_grid", TextFormatting.GREEN,
                        job.targetResult().getDisplayName()));
                return;
            }
            CraftExecutor.CraftResult result = CraftExecutor.craftOnce(player, job.currentStep().recipeId());
            if (result instanceof CraftExecutor.CraftResult.Success) {
                lastCrafted = ((CraftExecutor.CraftResult.Success) result).crafted();
                boolean stepDone = job.onCraftPerformed();
                if (job.isFinished()) {
                    ACTIVE.remove(player.getUniqueID());
                    sync(player, job, CraftProgressMessage.State.FINISHED, lastCrafted);
                    notify(player, message("sprawlcrafting.craft.finished", TextFormatting.GREEN,
                            job.targetResult().getDisplayName()));
                    return;
                }
                if (stepDone || !bulk) {
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
                // genuine shortfall — e.g. a hopper drained it — still cancels, as before.)
                if (job.externalDependent() && !ExternalSlots.present(player)) {
                    job.holdForRetry();
                    sync(player, job, CraftProgressMessage.State.PAUSED_STATION, ItemStack.EMPTY);
                    return;
                }
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
        // A bulk burst hit its per-tick cap (count or time) mid-step; the step stays due and continues next
        // tick. Report progress at most once per half second so the toast (and its step sound) keeps its cadence.
        if (job.progressSyncDue()) {
            sync(player, job, CraftProgressMessage.State.CRAFTING, lastCrafted);
        }
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
     * wedges forever on a base with no vanilla table. Cheap (a walk of the open container's slots), so
     * the burst ticks between table scans use it as-is.
     */
    private static boolean hasOpenCrafter(EntityPlayerMP player) {
        return GridContext.current(player) == GridContext.CRAFTING_TABLE;
    }

    /**
     * Which pause to report: a job started at a Crafting Station points the player back to it
     * ("open station" — its grid and its chest both live there), otherwise the classic "need table".
     * Without this a station-only base, where the resolution is to reopen the station rather than
     * place a vanilla table, would show a misleading hint and look wedged.
     */
    private static CraftProgressMessage.State pausedState(CraftJob job) {
        return job.externalDependent()
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
