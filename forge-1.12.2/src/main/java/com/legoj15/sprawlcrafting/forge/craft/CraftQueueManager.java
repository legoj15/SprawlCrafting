package com.legoj15.sprawlcrafting.forge.craft;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * Server-side registry of each player's active craft job. v1 allows a single job per player:
 * enqueueing while one is running is rejected. Java-8 port of the modern queue manager, minus the
 * final-grid hand-off (this port always auto-crafts).
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
        sync(player, job, CraftProgressMessage.State.CRAFTING, ItemStack.EMPTY);
        return true;
    }

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /** Advances the player's job by one tick; performs a craft when one comes due. */
    public static void tick(EntityPlayerMP player) {
        CraftJob job = ACTIVE.get(player.getUniqueID());
        if (job == null || !job.tick()) {
            return;
        }
        // 3x3 steps only run with full-grid access; re-check every half second.
        if (job.currentStep().needsFullGrid() && !hasFullGridAccess(player)) {
            job.holdForRetry();
            sync(player, job, pausedState(job), ItemStack.EMPTY);
            return;
        }
        CraftExecutor.CraftResult result = CraftExecutor.craftOnce(player, job.currentStep().recipeId());
        if (result instanceof CraftExecutor.CraftResult.Success) {
            ItemStack crafted = ((CraftExecutor.CraftResult.Success) result).crafted();
            job.onCraftPerformed();
            if (job.isFinished()) {
                ACTIVE.remove(player.getUniqueID());
                sync(player, job, CraftProgressMessage.State.FINISHED, crafted);
                notify(player, message("sprawlcrafting.craft.finished", TextFormatting.GREEN,
                        job.targetResult().getDisplayName()));
            } else {
                sync(player, job, CraftProgressMessage.State.CRAFTING, crafted);
            }
        } else if (result instanceof CraftExecutor.CraftResult.MissingIngredient) {
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
    }

    /**
     * Whether a 3x3 step may run right now: a vanilla crafting table within reach, or a 3x3 crafter
     * open this instant. The open-container clause covers a Tinkers' Construct Crafting Station,
     * which {@link CraftingTableReach} (it scans only for the vanilla table block) cannot see — so a
     * chain started at a station no longer wedges forever on a base with no vanilla table.
     */
    private static boolean hasFullGridAccess(EntityPlayerMP player) {
        return CraftingTableReach.isInReach(player)
                || GridContext.current(player) == GridContext.CRAFTING_TABLE;
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
        SprawlNetwork.sendProgress(player, new CraftProgressMessage(
                state, job.targetResult().copy(), current.copy(), job.craftsDone(), job.totalCrafts()));
    }
}
