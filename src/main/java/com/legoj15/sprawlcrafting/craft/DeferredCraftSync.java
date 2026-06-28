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
     * Debounce key per player: a hash of (open-menu identity, grid, main-inventory contents,
     * RecipeManager identity). Recompute only when one of those changes — opening/closing a
     * grid, gaining/losing/moving an item, or a datapack reload (which swaps the server's
     * RecipeManager and reassigns every RecipeDisplayId, so the client's synced set must be
     * replaced) — not every tick. The inventory is hashed by contents, not via
     * {@code getTimesChanged()}: that counter is not bumped by item pickup / {@code /give}
     * on the server (see {@link #maybeSync}), so keying on it would miss gathered materials.
     */
    private static final Map<UUID, Long> LAST_SYNC = new ConcurrentHashMap<>();

    /** Server tick of each player's last actual push, for the {@link #SYNC_THROTTLE_TICKS} coalesce. */
    private static final Map<UUID, Integer> LAST_SYNC_TICK = new ConcurrentHashMap<>();

    /**
     * Coalesce window: at most one deferred-set reclassify+push per player per this many ticks, so a
     * burst of inventory edits (shift-clicking a stack apart, an influx of items) collapses into one
     * push instead of reclassifying ~all recipes every tick. A deferred change is never dropped — the
     * debounce key still differs, so it re-fires once the window passes.
     */
    private static final int SYNC_THROTTLE_TICKS = 10;

    /**
     * If the player has a crafting screen open and its (menu, grid, inventory contents) state changed
     * since the last push, reclassify all crafting recipes and sync the deferred-only set. Gated to a
     * screen actually being open (and rate-limited) so idle/gathering players cost nothing. No-op on
     * 1.21.1.
     */
    public static void maybeSync(ServerPlayer player) {
        //? if >=1.21.11 {
        /*if (!(player.containerMenu instanceof net.minecraft.world.inventory.AbstractCraftingMenu menu)) {
            LAST_SYNC.remove(player.getUUID());
            LAST_SYNC_TICK.remove(player.getUUID());
            return; // a non-crafting container (or nothing) — nothing to classify
        }
        // Only classify/push while the client actually has a crafting screen open (reported via
        // CraftingScreenStatePayload — the server can't see the 2x2 inventory screen itself). The
        // deferred set is only ever read by the open recipe book, so reclassifying the full recipe
        // set for players merely walking around gathering (the bulk of inventory churn) is wasted
        // work. Trade-off: a recipe that became craftable while the screen was CLOSED outlines
        // correctly only after the open round-trip (a brief red->yellow on the first frames after
        // opening). Don't touch LAST_SYNC here, so reopening re-pushes only if the inventory changed.
        if (ClientCraftingView.open(player) == null) {
            return;
        }
        GridContext grid = (menu.getGridWidth() >= 3 && menu.getGridHeight() >= 3)
                ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
        // /reload swaps MinecraftServer.resources (a fresh RecipeManager instance) and
        // reallocates all display ids, so the manager's identity must be part of the key.
        net.minecraft.world.item.crafting.RecipeManager recipes = player.level().getServer().getRecipeManager();
        // Debounce on the real classification inputs: open menu, grid, recipe set, and the MAIN
        // inventory contents. We HASH the contents rather than read getInventory().getTimesChanged():
        // the server's player inventory only bumps timesChanged on container-slot edits, never on
        // Inventory.add (the path item pickup and /give take — add -> addResource -> setItem, none
        // of which call setChanged). A timesChanged-keyed debounce therefore misses a player
        // gathering raw materials and leaves the deferred set stale — the recipe that just became
        // craftable stays stuck on the red sprite until some unrelated slot edit forces a re-sync.
        long key = 1469598103934665603L; // 64-bit FNV-1a basis
        key = (key ^ System.identityHashCode(menu)) * 1099511628211L;
        key = (key ^ grid.ordinal()) * 1099511628211L;
        key = (key ^ System.identityHashCode(recipes)) * 1099511628211L;
        key = (key ^ inventorySignature(player.getInventory())) * 1099511628211L;
        Long prev = LAST_SYNC.get(player.getUUID());
        if (prev != null && prev == key) {
            return; // (menu, grid, inventory contents, recipes) unchanged since the last push
        }
        // Coalesce bursts (see SYNC_THROTTLE_TICKS). The key still differs from prev, so a change
        // deferred here re-fires on a later tick once the window passes — never dropped.
        // Clock = the SERVER tick (monotonic for the server's lifetime, the scope of LAST_SYNC_TICK's
        // UUID keys). NOT player.tickCount: that is per-entity and resets to 0 on death/respawn while
        // the UUID-keyed entry survives, which would wedge the sync for minutes (the respawned entity's
        // small tickCount minus the dead one's large value is negative, always < the window). The
        // now >= lastTick guard makes any backwards clock (e.g. int overflow after years of uptime)
        // self-heal by pushing immediately instead of stalling.
        int now = player.level().getServer().getTickCount();
        Integer lastTick = LAST_SYNC_TICK.get(player.getUUID());
        if (lastTick != null && now >= lastTick && now - lastTick < SYNC_THROTTLE_TICKS) {
            return;
        }
        if (!Services.PLATFORM.canReceive(player, DeferredCraftableSyncPayload.TYPE.id())) {
            return; // vanilla/modless client — record nothing, so we retry if it can ever receive
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
                new DeferredCraftableSyncPayload(grid.ordinal(), displayIds, recipeIds)));
        // Record only after a successful push, so a tick that was throttled or that the client could
        // not receive doesn't poison the debounce (it would otherwise mark this state "synced" without
        // the client ever getting it).
        LAST_SYNC.put(player.getUUID(), key);
        LAST_SYNC_TICK.put(player.getUUID(), now);*/
        //?}
    }

    //? if >=1.21.11 {
    /*// Order-sensitive 64-bit fold over the 36 main inventory slots — the exact pool the planner
    // reads ({@code CraftPlanner.snapshotInventory}). Cheap enough to run every tick, and unlike
    // {@code getTimesChanged()} it reflects pickups/{@code /give} (see {@link #maybeSync}). It can
    // over-detect (an item moved between slots re-syncs needlessly) but never under-detects a real
    // change to which items, and how many, are held.
    private static long inventorySignature(net.minecraft.world.entity.player.Inventory inventory) {
        long sig = 1469598103934665603L;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            int item = stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem());
            int count = stack.isEmpty() ? 0 : stack.getCount();
            sig = (sig ^ item) * 1099511628211L;
            sig = (sig ^ count) * 1099511628211L;
        }
        return sig;
    }*/
    //?}

    /** Forget a player's debounce state (disconnect). No-op on 1.21.1. */
    public static void clear(UUID playerId) {
        LAST_SYNC.remove(playerId);
        LAST_SYNC_TICK.remove(playerId);
    }

    /**
     * Forget every player's debounce state (server stopping). Mirrors
     * {@code CraftQueueManager.clearAll()} so this sibling per-player map cannot leak entries
     * across worlds on the integrated server. No-op on 1.21.1 (the map is always empty there).
     */
    public static void clearAll() {
        LAST_SYNC.clear();
        LAST_SYNC_TICK.clear();
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
            // Vanilla parity: the first click on a recipe clears the grid back to the inventory.
            // Do it here so a single click on a yellow recipe behaves like any vanilla recipe
            // click, and so the preview plan below reflects the freed grid items.
            CraftExecutor.clearOpenCraftingGrid(player);
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

    /** Server handler: gather-list request from the recipe book (display index). No-op on 1.21.1. */
    public static void handleShortfallByDisplay(ServerPlayer player, int token, int displayId) {
        //? if >=1.21.11 {
        /*net.minecraft.world.item.crafting.RecipeManager recipes = player.level().getServer().getRecipeManager();
        net.minecraft.world.item.crafting.RecipeManager.ServerDisplayInfo info =
                recipes.getRecipeFromDisplay(new net.minecraft.world.item.crafting.display.RecipeDisplayId(displayId));
        sendShortfall(player, token, info == null ? null : info.parent());*/
        //?}
    }

    /** Server handler: gather-list request from a recipe viewer (recipe id). No-op on 1.21.1. */
    public static void handleShortfallByRecipe(ServerPlayer player, int token,
                                               net.minecraft.resources.ResourceLocation recipeId) {
        //? if >=1.21.11 {
        /*sendShortfall(player, token, RecipeIds.byId(player.level().getServer().getRecipeManager(), recipeId).orElse(null));*/
        //?}
    }

    //? if >=1.21.11 {
    /*// Compute the gather list (informational → always cost the full 3×3 chain, never mutate the
    // grid the way the preview handler does) and stream it back, echoing the request token.
    private static void sendShortfall(ServerPlayer player, int token,
                                      net.minecraft.world.item.crafting.RecipeHolder<?> holder) {
        ShortfallView view = holder == null
                ? ShortfallView.unavailable()
                : CraftPlanner.shortfall(player, holder, GridContext.CRAFTING_TABLE);
        if (Services.PLATFORM.canReceive(player, com.legoj15.sprawlcrafting.network.ShortfallPayload.TYPE.id())) {
            player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                    new com.legoj15.sprawlcrafting.network.ShortfallPayload(
                            token, view.targetItem(), view.targetCount(), view.approximate(), view.demands())));
        }
    }*/
    //?}
}
