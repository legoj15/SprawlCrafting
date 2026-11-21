package com.legoj15.sprawlcrafting.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.StationStorages;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.util.ResourceLocation;

/**
 * Client-side answer to "is this recipe craftable from raw resources right now?" for the recipe book
 * (yellow outlines, the craftable filter, click previews) and the JEI transfer button. 1.16.5 clients
 * receive every recipe, so this runs a {@link CraftPlanner.Session} over the synced RecipeManager and
 * the inventory mirror with no server round trip. With a usable crafting station open (an AE2 Crafting
 * Terminal) its storage joins the pool, as it does on the server.
 *
 * <p>Offers are suppressed entirely when the server lacks the mod ({@code serverHasMod}): a start packet
 * could not be delivered there, so showing yellow would be a lie. The same rule gates the protected
 * tier (issue #40): a second session that also counts the player's protected stacks — and, when the
 * server negotiated the station-protected marker (issue #49), the open station's — is consulted only
 * when the server has the protected channel ({@code canStartProtectedCraft}) and either source holds
 * any, and a recipe only that session can make is {@code DEFERRED} (never direct) and remembered in
 * {@code protectedOnly}, so its confirm click sends the protected start packet.
 */
public final class DeferredCraftableCache {

    private static CraftPlanner.Session session;
    /** The same pool plus the player's protected stacks; null unless protected planning is possible. */
    private static CraftPlanner.Session protectedSession;
    private static int inventoryGeneration = -1;
    private static GridContext grid;
    private static RecipeManager recipeManager;
    /** The {@link #stationCounts()} instance the sessions were built with (compared by reference). */
    private static Map<Item, Long> sessionStation;
    /** The {@link #stationProtectedCounts()} instance the sessions were built with (issue #49). */
    private static Map<Item, Long> sessionStationProtected;
    /** Recipes we marked craftable that vanilla did NOT — these get the yellow outline. */
    private static final Set<ResourceLocation> deferredOnly = new HashSet<>();
    /** Of those, the recipes only a protected-including plan can make (issue #40). */
    private static final Set<ResourceLocation> protectedOnly = new HashSet<>();

    private static Container stationMenu;
    private static long stationTick = Long.MIN_VALUE;
    private static Map<Item, Long> stationCounts = Collections.emptyMap();
    private static boolean stationUsable;
    /** The protected mirrors' own cache slots (issue #49), refreshed with the same once-per-tick rule. */
    private static Container stationProtectedMenu;
    private static long stationProtectedTick = Long.MIN_VALUE;
    private static Map<Item, Long> stationProtectedCounts = Collections.emptyMap();
    private static Map<Item, ItemStack> stationProtectedSamples = Collections.emptyMap();

    private DeferredCraftableCache() {
    }

    /** Grid context for the recipe book pass, from the canCraft width/height arguments. */
    public static GridContext gridFor(int width, int height) {
        return GridContext.forSize(width, height);
    }

    /**
     * The plain-item counts of the crafting station the player has open (an AE2 Crafting Terminal's ME
     * network, from its client repo; see {@link StationStorages}), empty for none. Recomputed at most once
     * per client tick (and when the open menu changes): JEI classifies many recipes per frame, and the scan
     * is linear in the storage's size. The returned map keeps its identity while the counts are unchanged,
     * so callers can compare by reference to see a change. Empty, too, against a server that cannot draw
     * from a station ({@code serverSupportsStations}): it would refuse what the counts offered.
     */
    public static Map<Item, Long> stationCounts() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPlayerEntity player = minecraft.player;
        if (player == null || minecraft.level == null) {
            stationUsable = false;
            return Collections.emptyMap();
        }
        Container menu = player.containerMenu;
        long tick = minecraft.level.getGameTime();
        if (menu != stationMenu || tick != stationTick) {
            Map<Item, Long> counts = minecraft.getConnection() != null && Services.PLATFORM.serverSupportsStations()
                    ? StationStorages.clientCounts(menu) : null;
            stationUsable = counts != null;
            Map<Item, Long> next = counts == null ? Collections.<Item, Long>emptyMap() : counts;
            if (!next.equals(stationCounts)) {
                stationCounts = next;
            }
            stationMenu = menu;
            stationTick = tick;
        }
        return stationCounts;
    }

    /**
     * Whether the open menu is a usable crafting station the server can draw from (an unpowered AE2
     * terminal is not), from the same once-per-tick cache as {@link #stationCounts()}: the JEI handler asks
     * this per evaluation.
     */
    public static boolean stationUsable() {
        stationCounts();
        return stationUsable;
    }

    /**
     * The protected-stack counts of the open crafting station (issue #49), empty for none — cached
     * and identity-kept exactly like {@link #stationCounts()}. Gated one step further than it, too: a
     * server without the station-protected marker would plan a protected start from the plain and
     * inventory-protected pools only and refuse it, so these count only when both protected starts
     * ({@code canStartProtectedCraft}) and station-protected planning
     * ({@code serverSupportsStationProtected}) are negotiated.
     */
    public static Map<Item, Long> stationProtectedCounts() {
        refreshStationProtected();
        return stationProtectedCounts;
    }

    /**
     * One count-1 sample per item of {@link #stationProtectedCounts()} (issue #49), same cache and
     * gate; only read while the counts are non-empty, so the no-protected-station case costs one
     * scan, not two.
     */
    public static Map<Item, ItemStack> stationProtectedSamples() {
        refreshStationProtected();
        return stationProtectedSamples;
    }

    /** The once-per-tick (and per menu change) refresh behind both protected mirrors (issue #49). */
    private static void refreshStationProtected() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPlayerEntity player = minecraft.player;
        if (player == null || minecraft.level == null) {
            stationProtectedCounts = Collections.emptyMap();
            stationProtectedSamples = Collections.emptyMap();
            return;
        }
        Container menu = player.containerMenu;
        long tick = minecraft.level.getGameTime();
        if (menu != stationProtectedMenu || tick != stationProtectedTick) {
            Map<Item, Long> counts = null;
            if (minecraft.getConnection() != null && Services.PLATFORM.canStartProtectedCraft()
                    && Services.PLATFORM.serverSupportsStationProtected()) {
                counts = StationStorages.clientProtectedCounts(menu);
            }
            Map<Item, Long> nextCounts = counts == null ? Collections.<Item, Long>emptyMap() : counts;
            // The samples' items are exactly the counts' (the provider scans both in one pass), so the
            // second scan runs only when there is something to sample.
            Map<Item, ItemStack> samples = nextCounts.isEmpty() ? Collections.<Item, ItemStack>emptyMap()
                    : StationStorages.clientProtectedSamples(menu);
            Map<Item, ItemStack> nextSamples = samples == null
                    ? Collections.<Item, ItemStack>emptyMap() : samples;
            if (!nextCounts.equals(stationProtectedCounts)) {
                stationProtectedCounts = nextCounts;
            }
            if (!nextSamples.equals(stationProtectedSamples)) {
                stationProtectedSamples = nextSamples;
            }
            stationProtectedMenu = menu;
            stationProtectedTick = tick;
        }
    }

    public static boolean isSolvable(IRecipe<?> recipe, GridContext requestGrid) {
        return classify(recipe, requestGrid) != CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Direct vs deferred vs unsolvable, for recipe-viewer (JEI) button states. */
    public static CraftPlanner.Craftability classify(IRecipe<?> recipe, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        if (current == null) {
            return CraftPlanner.Craftability.UNSOLVABLE;
        }
        CraftPlanner.Craftability plain = current.classify(recipe);
        if (plain != CraftPlanner.Craftability.UNSOLVABLE || protectedSession == null) {
            return plain;
        }
        if (protectedSession.classify(recipe) != CraftPlanner.Craftability.UNSOLVABLE) {
            // Makeable only by consuming protected stacks: deferred, never direct — a one-step craft
            // of that kind must run through our executor too (vanilla's own recipe-book placement
            // refuses enchanted/renamed stacks) — and remembered so the confirm sends the protected
            // start instead of the plain one.
            protectedOnly.add(recipe.getId());
            return CraftPlanner.Craftability.DEFERRED;
        }
        return CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Marks a recipe as craftable-only-via-deferral, for the yellow button tint. */
    public static void markDeferredOnly(IRecipe<?> recipe) {
        deferredOnly.add(recipe.getId());
    }

    public static boolean isDeferredOnly(IRecipe<?> recipe) {
        return recipe != null && deferredOnly.contains(recipe.getId());
    }

    /** Whether {@code recipe} is makeable only by consuming protected stacks (see {@link #classify}). */
    public static boolean isProtectedOnly(IRecipe<?> recipe) {
        return recipe != null && protectedOnly.contains(recipe.getId());
    }

    /**
     * A full plan for the click preview, from the same session the outlines used. A recipe only the
     * protected session can make gets that plan, with {@code usesProtected} set — the preview's
     * warning lines and the confirm click both key off it.
     */
    public static CraftPlanner.PlanOutcome plan(IRecipe<?> recipe, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        if (current == null) {
            return CraftPlanner.PlanOutcome.of(CraftPlanner.PlanOutcome.Kind.UNSUPPORTED);
        }
        CraftPlanner.PlanOutcome outcome = current.plan(recipe);
        if (outcome.isPlanned() || protectedSession == null) {
            return outcome;
        }
        CraftPlanner.PlanOutcome withProtected = protectedSession.plan(recipe);
        if (withProtected.isPlanned()) {
            withProtected.job().setUsesProtected(true);
            return withProtected;
        }
        return outcome; // the plain rejection, with its own message semantics
    }

    /**
     * Drops the session outright — needed on datapack reload, where the client RecipeManager is mutated
     * in place and identity comparison cannot see the change. The planner's producer index is keyed on
     * that same identity, so it must be evicted too.
     */
    public static void invalidate() {
        if (recipeManager != null) {
            CraftPlanner.invalidateProducerIndex(recipeManager);
            recipeManager = null;
        }
        session = null;
        protectedSession = null;
        sessionStation = null;
        sessionStationProtected = null;
        stationMenu = null;
        stationTick = Long.MIN_VALUE;
        stationCounts = Collections.emptyMap();
        stationUsable = false;
        stationProtectedMenu = null;
        stationProtectedTick = Long.MIN_VALUE;
        stationProtectedCounts = Collections.emptyMap();
        stationProtectedSamples = Collections.emptyMap();
        deferredOnly.clear();
        protectedOnly.clear();
    }

    private static CraftPlanner.Session currentSession(GridContext requestGrid) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPlayerEntity player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.getConnection() == null
                || !Services.PLATFORM.serverHasMod()) {
            return null;
        }
        int generation = player.inventory.getTimesChanged();
        RecipeManager recipes = minecraft.level.getRecipeManager();
        // An open crafting station's storage (AE2 Crafting Terminal) joins the pool, as on the server.
        Map<Item, Long> station = stationCounts();
        Map<Item, Long> stationProtected = stationProtectedCounts();
        if (session == null || generation != inventoryGeneration
                || requestGrid != grid || recipes != recipeManager || station != sessionStation
                || stationProtected != sessionStationProtected) {
            session = CraftPlanner.session(recipes, player.inventory, requestGrid, station);
            // The second pass over the same pool plus the protected stacks (issue #40) — inventory
            // ones, and the station's too (issue #49) — built only when the server can honour a
            // protected start and either source holds any: everything else keeps the plain answer.
            // Any change that flips either (an inventory generation, a new connection, the station's
            // protected map changing identity) also invalidates this whole block, so it cannot go stale.
            protectedSession = Services.PLATFORM.canStartProtectedCraft()
                    && (CraftPlanner.hasProtected(player.inventory) || !stationProtected.isEmpty())
                    ? CraftPlanner.session(recipes, player.inventory, requestGrid, station,
                            stationProtected, true) : null;
            inventoryGeneration = generation;
            grid = requestGrid;
            recipeManager = recipes;
            sessionStation = station;
            sessionStationProtected = stationProtected;
            deferredOnly.clear();
            protectedOnly.clear();
        }
        return session;
    }
}
