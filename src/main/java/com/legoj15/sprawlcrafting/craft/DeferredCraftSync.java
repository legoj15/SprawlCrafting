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
     * Debounce key per player: a hash of (open-menu identity, grid, main-inventory contents, the
     * open grid's contents (issue #50), open-station storage counts, RecipeManager identity).
     * Recompute only when one of those changes — opening/closing a grid, gaining/losing/moving
     * an item, or a datapack reload (which swaps the server's RecipeManager and reassigns every
     * RecipeDisplayId, so the client's synced set must be replaced) — not every tick. The
     * inventory is hashed by contents, not via
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
     * Each player's last open-station storage scan ({@code StationStorage#counts} plus its protected
     * tier, issue #49 — each a full storage scan), reused for up to {@link #SYNC_THROTTLE_TICKS} ticks
     * while the same menu stays open, so an idle station screen costs one scan per window instead of
     * one per tick. A change shows up within one window, the same latency the push throttle already
     * allows. Empty on 1.21.1.
     */
    private static final Map<UUID, StationScan> STATION_SCANS = new ConcurrentHashMap<>();

    /** One scan's plain {@code counts} and protected {@code protectedCounts} (issue #49). */
    private record StationScan(Object menu, int tick, Map<net.minecraft.world.item.Item, Long> counts,
                               Map<net.minecraft.world.item.Item, Long> protectedCounts) {
    }

    /**
     * If the player has a crafting screen open and its (menu, grid, inventory contents) state changed
     * since the last push, reclassify all crafting recipes and sync the deferred-only set. Gated to a
     * screen actually being open (and rate-limited) so idle/gathering players cost nothing. No-op on
     * 1.21.1.
     */
    public static void maybeSync(ServerPlayer player) {
        //? if >=1.21.11 {
        /*// CraftingGrids, not AbstractCraftingMenu: a modded grid (the Sophisticated crafting upgrade) is a
        // crafting screen too, and the client reports it the same way.
        net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
        CraftingGrids.Grid openGrid = CraftingGrids.find(menu);
        if (openGrid == null) {
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
        // The open station's storage (issue #2, the AE2 terminal; #33, a Sophisticated screen with its
        // crafting upgrade) joins the pool the classification plans against — the same fold the 1.21.1
        // client's local solve makes (DeferredCraftableCache.stationCounts) — its plain counts here,
        // and its protected tier (#49) for the protected-only pass below, both from the one cached scan.
        StationScan scan = stationScan(player, menu);
        Map<net.minecraft.world.item.Item, Long> stationCounts = scan.counts();
        Map<net.minecraft.world.item.Item, Long> stationProtected = scan.protectedCounts();
        GridContext grid = CraftingGrids.isFullGrid(openGrid) ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
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
        // The open grid's own contents (issue #50) are a classification input now — the planner
        // counts what clearOpenCraftingGrid would return to the main slots — and they change while
        // neither the menu nor the main inventory does (items moved into the grid), so they fold
        // into the key the same way.
        key = (key ^ gridSignature(openGrid)) * 1099511628211L;
        key = (key ^ stationCounts.hashCode()) * 1099511628211L;
        key = (key ^ stationProtected.hashCode()) * 1099511628211L;
        Long prev = LAST_SYNC.get(player.getUUID());
        if (prev != null && prev == key) {
            return; // (menu, grid, inventory + open-grid contents, station storage, recipes) unchanged since the last push
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
                player.getInventory(), grid, stationCounts);
        java.util.Set<Integer> displayIds = new java.util.HashSet<>();
        java.util.Set<net.minecraft.resources.ResourceLocation> recipeIds = new java.util.HashSet<>();
        for (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> holder
                : RecipeIds.craftingRecipes(recipes)) {
            if (session.classify(holder) == CraftPlanner.Craftability.DEFERRED) {
                recipeIds.add(holder.id().identifier());
                recipes.listDisplaysForRecipe(holder.id(), entry -> displayIds.add(entry.id().index()));
            }
        }
        Services.PLATFORM.sendToPlayer(player,
                new DeferredCraftableSyncPayload(grid.ordinal(), displayIds, recipeIds));
        // The protected tier (#40), right after the deferred set: recipes the plain pool cannot make
        // but the protected tier can — the player's stacks (#40) or the open station's (#49). An
        // empty set clears stale yellows just as well (the last enchanted copy was used up), so this
        // goes to every client that can receive it; the second, protected-counting session is built
        // only while either side holds any. Only plain-UNSOLVABLE recipes enter (a plain-DEFERRED one
        // is already yellow and confirms with the plain packet); the client treats members as deferred
        // and confirms them with the protected start.
        if (Services.PLATFORM.canReceive(player,
                com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload.TYPE.id())) {
            java.util.Set<Integer> protectedDisplayIds = new java.util.HashSet<>();
            java.util.Set<net.minecraft.resources.ResourceLocation> protectedRecipeIds = new java.util.HashSet<>();
            CraftPlanner.Session withProtected = CraftPlanner.hasProtected(player.getInventory())
                    || !stationProtected.isEmpty()
                    ? CraftPlanner.session(recipes, player.registryAccess(), player.getInventory(), grid,
                            stationCounts, stationProtected, true)
                    : null;
            if (withProtected != null) {
                for (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> holder
                        : RecipeIds.craftingRecipes(recipes)) {
                    if (session.classify(holder) == CraftPlanner.Craftability.UNSOLVABLE
                            && withProtected.classify(holder) != CraftPlanner.Craftability.UNSOLVABLE) {
                        protectedRecipeIds.add(holder.id().identifier());
                        recipes.listDisplaysForRecipe(holder.id(), entry -> protectedDisplayIds.add(entry.id().index()));
                    }
                }
            }
            Services.PLATFORM.sendToPlayer(player,
                    new com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload(
                            grid.ordinal(), protectedDisplayIds, protectedRecipeIds));
        }
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
    // change to which items, and how many, are held. Each stack's protected state (#40:
    // enchanted and/or renamed) folds in too — enchanting or renaming a held stack in place changes
    // neither its item nor its count, yet flips the protected-only classification.
    private static long inventorySignature(net.minecraft.world.entity.player.Inventory inventory) {
        long sig = 1469598103934665603L;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            int item = stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem());
            int count = stack.isEmpty() ? 0 : stack.getCount();
            sig = (sig ^ item) * 1099511628211L;
            sig = (sig ^ count) * 1099511628211L;
            sig = (sig ^ (com.legoj15.sprawlcrafting.craft.CraftExecutor.isProtected(stack) ? 1 : 0))
                    * 1099511628211L;
        }
        return sig;
    }

    // The open grid's input cells (issue #50), the same per-stack fold as inventorySignature: their
    // contents are a classification input now (the planner counts what clearOpenCraftingGrid would
    // return to the main slots), and they change while neither the menu nor the main inventory does.
    // As there, the protected state folds in — a grid stack flipping protected changes neither its
    // item nor its count, yet flips the protected-only pass.
    private static long gridSignature(CraftingGrids.Grid grid) {
        long sig = 1469598103934665603L;
        for (net.minecraft.world.inventory.Slot slot : grid.inputs()) {
            net.minecraft.world.item.ItemStack stack = slot.getItem();
            int item = stack.isEmpty() ? 0 : System.identityHashCode(stack.getItem());
            int count = stack.isEmpty() ? 0 : stack.getCount();
            sig = (sig ^ item) * 1099511628211L;
            sig = (sig ^ count) * 1099511628211L;
            sig = (sig ^ (CraftExecutor.isProtected(stack) ? 1 : 0)) * 1099511628211L;
        }
        return sig;
    }*/
    //?}

    //? if >=1.21.11 {
    /*// The open station's plain and protected counts in one scan (see STATION_SCANS): rescanned only
    // for a new menu, or once the cached scan is a throttle window old.
    private static StationScan stationScan(ServerPlayer player,
                                           net.minecraft.world.inventory.AbstractContainerMenu menu) {
        int now = player.level().getServer().getTickCount();
        StationScan scan = STATION_SCANS.get(player.getUUID());
        if (scan == null || scan.menu() != menu || now < scan.tick() || now - scan.tick() >= SYNC_THROTTLE_TICKS) {
            StationStorage station = StationStorages.open(player);
            scan = station == null
                    ? new StationScan(menu, now, Map.of(), Map.of())
                    : new StationScan(menu, now, station.counts(), station.protectedCounts());
            STATION_SCANS.put(player.getUUID(), scan);
        }
        return scan;
    }*/
    //?}

    /** Forget a player's debounce state (disconnect). No-op on 1.21.1. */
    public static void clear(UUID playerId) {
        LAST_SYNC.remove(playerId);
        LAST_SYNC_TICK.remove(playerId);
        STATION_SCANS.remove(playerId);
    }

    /**
     * Forget every player's debounce state (server stopping). Mirrors
     * {@code CraftQueueManager.clearAll()} so this sibling per-player map cannot leak entries
     * across worlds on the integrated server. No-op on 1.21.1 (the map is always empty there).
     */
    public static void clearAll() {
        LAST_SYNC.clear();
        LAST_SYNC_TICK.clear();
        STATION_SCANS.clear();
    }

    /** Server handler: a recipe-book click confirmed by display index — map it back and start. No-op on 1.21.1. */
    public static void handleStartByDisplay(ServerPlayer player, int displayId) {
        handleStartByDisplay(player, displayId, false);
    }

    /**
     * {@link #handleStartByDisplay(ServerPlayer, int)} with the protected tier (issue #40): the
     * confirmed request whose plan may count the player's protected stacks. No-op on 1.21.1.
     */
    public static void handleStartByDisplay(ServerPlayer player, int displayId, boolean allowProtected) {
        //? if >=1.21.11 {
        /*net.minecraft.world.item.crafting.RecipeManager recipes = player.level().getServer().getRecipeManager();
        net.minecraft.world.item.crafting.RecipeManager.ServerDisplayInfo info =
                recipes.getRecipeFromDisplay(new net.minecraft.world.item.crafting.display.RecipeDisplayId(displayId));
        if (info != null) {
            CraftRequests.handleStartRequest(player, info.parent().id().identifier(), allowProtected);
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
            // Planned as CraftRequests will start it: the inventory first, then with the open station's storage.
            CraftPlanner.PlanOutcome outcome = CraftPlanner.plan(player, info.parent(), grid);
            StationStorage station = outcome instanceof CraftPlanner.PlanOutcome.Planned ? null : StationStorages.open(player);
            java.util.Map<net.minecraft.world.item.Item, Long> stationCounts =
                    station == null ? java.util.Map.of() : station.counts();
            java.util.Map<net.minecraft.world.item.Item, Long> stationProtectedCounts = java.util.Map.of();
            if (station != null) {
                outcome = CraftPlanner.plan(player, info.parent(), grid, stationCounts);
            }
            // The protected fallback (#40), same order and gates as the start handler: only when both
            // plain passes failed, only for a client that can receive the protected sync (its confirm
            // click keys off that set), and only while the player (#40) or the station (#49) holds
            // protected stock — the station's counts scanned only when a pass needs them. The job is
            // marked usesProtected, and CraftPreview.lines appends the warning lines for it.
            if (!(outcome instanceof CraftPlanner.PlanOutcome.Planned)
                    && Services.PLATFORM.canReceive(player,
                            com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload.TYPE.id())) {
                boolean inventoryProtected = CraftPlanner.hasProtected(player.getInventory());
                if (!inventoryProtected) {
                    stationProtectedCounts = station == null
                            ? java.util.Map.of() : station.protectedCounts();
                }
                if (inventoryProtected || !stationProtectedCounts.isEmpty()) {
                    CraftPlanner.PlanOutcome withProtected = inventoryProtected
                            ? CraftPlanner.plan(player, info.parent(), grid, java.util.Map.of(),
                                    java.util.Map.of(), true)
                            : null;
                    if (!(withProtected instanceof CraftPlanner.PlanOutcome.Planned) && station != null) {
                        if (stationProtectedCounts.isEmpty()) {
                            stationProtectedCounts = station.protectedCounts();
                        }
                        withProtected = CraftPlanner.plan(player, info.parent(), grid, stationCounts,
                                stationProtectedCounts, true);
                    }
                    if (withProtected instanceof CraftPlanner.PlanOutcome.Planned protectedPlanned) {
                        protectedPlanned.job().setUsesProtected(true);
                        outcome = withProtected;
                    }
                }
            }
            if (outcome instanceof CraftPlanner.PlanOutcome.Planned planned) {
                // stationCounts (empty when no station is open) ride along so the protected warning
                // credits the station's plain stock, which the executor draws before protected copies;
                // the protected counts and samples (#49) ride along for the same warning's station
                // lines, so they are scanned only for a job that would show them.
                java.util.Map<net.minecraft.world.item.Item, net.minecraft.world.item.ItemStack> stationProtectedSamples =
                        planned.job().usesProtected() && station != null
                                ? station.protectedSamples() : java.util.Map.of();
                lines = CraftPreview.lines(planned.job(), recipes, player.registryAccess(),
                        player.getInventory(), stationCounts, stationProtectedCounts, stationProtectedSamples);
            }
        }
        if (Services.PLATFORM.canReceive(player, com.legoj15.sprawlcrafting.network.CraftPreviewPayload.TYPE.id())) {
            Services.PLATFORM.sendToPlayer(player,
                    new com.legoj15.sprawlcrafting.network.CraftPreviewPayload(displayId, lines));
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
        // Protected stacks (#40) count for a client that takes the protected-only push (the same gate):
        // it can confirm a protected craft, so the list must not ask for what that craft uses (#48).
        boolean includeProtected = Services.PLATFORM.canReceive(player,
                com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload.TYPE.id());
        ShortfallView view = holder == null
                ? ShortfallView.unavailable()
                : CraftPlanner.shortfall(player, holder, GridContext.CRAFTING_TABLE, includeProtected);
        // Wire versioning: a client that registered the V2 channel gets long counts + the saturated
        // flag; a mod-1.0.3 client only knows the original payload, whose ItemDemand codec clamps each
        // count to Integer.MAX_VALUE; a modless client gets nothing.
        if (Services.PLATFORM.canReceive(player, com.legoj15.sprawlcrafting.network.ShortfallV2Payload.ID)) {
            Services.PLATFORM.sendToPlayer(player,
                    new com.legoj15.sprawlcrafting.network.ShortfallV2Payload(token, view.targetItem(),
                            view.targetCount(), view.approximate(), view.saturated(), view.demands()));
        } else if (Services.PLATFORM.canReceive(player, com.legoj15.sprawlcrafting.network.ShortfallPayload.ID)) {
            Services.PLATFORM.sendToPlayer(player,
                    new com.legoj15.sprawlcrafting.network.ShortfallPayload(
                            token, view.targetItem(), view.targetCount(), view.approximate(), view.demands()));
        }
    }*/
    //?}
}
