package com.legoj15.sprawlcrafting.craft;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-authoritative deferred-craftability sync — the 26.x replacement for the client-side
 * solver. On MC 1.21.1 the client has the full RecipeManager and computes yellow-outline
 * craftability itself ({@code DeferredCraftableCache}); on 26.x (>=1.21.11) the client has no
 * recipe contents at all, so the server (which still has the full RecipeManager) classifies
 * every crafting recipe for the player's open grid + inventory and pushes the deferred-only
 * subset via {@link DeferredCraftableSyncPayload}.
 *
 * <p>Everything here is gated to 26.x: on 1.21.1 {@link #maybeSync} and {@link #clear} are
 * no-ops, so the loader tick loops can call them unconditionally.
 */
public final class DeferredCraftSync {

    private DeferredCraftSync() {
    }

    /**
     * Debounce key per player: a hash of (open-menu identity, grid, inventory generation,
     * RecipeManager identity). Recompute only when one of those changes — opening/closing a
     * grid, moving an item, or a datapack reload (which swaps the server's RecipeManager and
     * reassigns every RecipeDisplayId, so the client's synced set must be replaced) — not
     * every tick.
     */
    private static final Map<UUID, Long> LAST_SYNC = new ConcurrentHashMap<>();

    /**
     * If the player has a crafting grid open and its (menu, grid, inventory) state changed since
     * the last push, reclassify all crafting recipes and sync the deferred-only set. No-op on 1.21.1.
     */
    public static void maybeSync(ServerPlayer player) {
        //? if >=1.21.11 {
        /*if (!(player.containerMenu instanceof net.minecraft.world.inventory.AbstractCraftingMenu menu)) {
            LAST_SYNC.remove(player.getUUID());
            return; // no crafting grid open — nothing to classify
        }
        GridContext grid = (menu.getGridWidth() >= 3 && menu.getGridHeight() >= 3)
                ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        // /reload swaps MinecraftServer.resources (a fresh RecipeManager instance) and
        // reallocates all display ids, so the manager's identity must be part of the key.
        net.minecraft.world.item.crafting.RecipeManager recipes = player.level().getServer().getRecipeManager();
        long key = (((long) System.identityHashCode(menu)) << 21)
                ^ ((long) grid.ordinal() << 19)
                ^ (player.getInventory().getTimesChanged() & 0x7FFFFL)
                ^ (((long) System.identityHashCode(recipes)) << 32);
        Long prev = LAST_SYNC.get(player.getUUID());
        if (prev != null && prev == key) {
            return; // (menu, grid, inventory, recipes) unchanged since the last push
        }
        LAST_SYNC.put(player.getUUID(), key);

        if (!Services.PLATFORM.canReceive(player, DeferredCraftableSyncPayload.TYPE.id())) {
            return; // vanilla/modless client — it has no recipe book hook to feed
        }
        CraftPlanner.Session session = CraftPlanner.session(recipes, player.registryAccess(),
                player.getInventory(), grid);
        java.util.Set<Integer> displayIds = new java.util.HashSet<>();
        java.util.Set<net.minecraft.resources.ResourceLocation> recipeIds = new java.util.HashSet<>();
        for (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> holder
                : RecipeIds.craftingRecipes(recipes)) {
            if (session.classify(holder) == CraftPlanner.Craftability.DEFERRED) {
                recipeIds.add(holder.id().identifier());
                recipes.listDisplaysForRecipe(holder.id(), entry -> displayIds.add(entry.id().index()));
            }
        }
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                new DeferredCraftableSyncPayload(grid.ordinal(), displayIds, recipeIds)));*/
        //?}
    }

    /** Forget a player's debounce state (disconnect). No-op on 1.21.1. */
    public static void clear(UUID playerId) {
        LAST_SYNC.remove(playerId);
    }

    /**
     * Forget every player's debounce state (server stopping). Mirrors
     * {@code CraftQueueManager.clearAll()} so this sibling per-player map cannot leak entries
     * across worlds on the integrated server. No-op on 1.21.1 (the map is always empty there).
     */
    public static void clearAll() {
        LAST_SYNC.clear();
    }

    /** Server handler: a recipe-book click confirmed by display index — map it back and start. No-op on 1.21.1. */
    public static void handleStartByDisplay(ServerPlayer player, int displayId) {
        //? if >=1.21.11 {
        /*net.minecraft.world.item.crafting.RecipeManager recipes = player.level().getServer().getRecipeManager();
        net.minecraft.world.item.crafting.RecipeManager.ServerDisplayInfo info =
                recipes.getRecipeFromDisplay(new net.minecraft.world.item.crafting.display.RecipeDisplayId(displayId));
        if (info != null) {
            CraftRequests.handleStartRequest(player, info.parent().id().identifier());
        }*/
        //?}
    }

    /** Server handler: first-click preview request — plan it and stream the breakdown back. No-op on 1.21.1. */
    public static void handlePreviewRequest(ServerPlayer player, int displayId) {
        //? if >=1.21.11 {
        /*net.minecraft.world.item.crafting.RecipeManager recipes = player.level().getServer().getRecipeManager();
        net.minecraft.world.item.crafting.RecipeManager.ServerDisplayInfo info =
                recipes.getRecipeFromDisplay(new net.minecraft.world.item.crafting.display.RecipeDisplayId(displayId));
        java.util.List<net.minecraft.network.chat.Component> lines = java.util.List.of();
        if (info != null) {
            GridContext grid = GridContext.current(player);
            if (CraftPlanner.plan(player, info.parent(), grid) instanceof CraftPlanner.PlanOutcome.Planned planned) {
                lines = CraftPreview.lines(planned.job(), recipes, player.registryAccess());
            }
        }
        if (Services.PLATFORM.canReceive(player, com.legoj15.sprawlcrafting.network.CraftPreviewPayload.TYPE.id())) {
            player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                    new com.legoj15.sprawlcrafting.network.CraftPreviewPayload(displayId, lines)));
        }*/
        //?}
    }
}
