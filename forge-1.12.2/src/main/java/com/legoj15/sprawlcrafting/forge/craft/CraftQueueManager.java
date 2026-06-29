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

    /** @return the cancelled job, if there was one. The caller reports to the player. */
    public static Optional<CraftJob> cancel(UUID playerId) {
        return Optional.ofNullable(ACTIVE.remove(playerId));
    }

    /** Toast sync for a manual cancel (command path, which has the player at hand). */
    public static void syncCancelled(EntityPlayerMP player, CraftJob job) {
        sync(player, job, CraftProgressMessage.State.CANCELLED, ItemStack.EMPTY);
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
        // 3x3 steps only run with a crafting table in reach; re-check every half second.
        if (job.currentStep().needsFullGrid() && !CraftingTableReach.isInReach(player)) {
            job.holdForRetry();
            sync(player, job, CraftProgressMessage.State.PAUSED, ItemStack.EMPTY);
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
