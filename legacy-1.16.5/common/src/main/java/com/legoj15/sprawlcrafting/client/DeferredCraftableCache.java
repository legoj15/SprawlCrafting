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
 * could not be delivered there, so showing yellow would be a lie.
 */
public final class DeferredCraftableCache {

    private static CraftPlanner.Session session;
    private static int inventoryGeneration = -1;
    private static GridContext grid;
    private static RecipeManager recipeManager;
    /** The {@link #stationCounts()} instance the session was built with (compared by reference). */
    private static Map<Item, Long> sessionStation;
    /** Recipes we marked craftable that vanilla did NOT — these get the yellow outline. */
    private static final Set<ResourceLocation> deferredOnly = new HashSet<>();

    private static Container stationMenu;
    private static long stationTick = Long.MIN_VALUE;
    private static Map<Item, Long> stationCounts = Collections.emptyMap();
    private static boolean stationUsable;

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

    public static boolean isSolvable(IRecipe<?> recipe, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null && current.isSolvable(recipe);
    }

    /** Direct vs deferred vs unsolvable, for recipe-viewer (JEI) button states. */
    public static CraftPlanner.Craftability classify(IRecipe<?> recipe, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null ? current.classify(recipe) : CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Marks a recipe as craftable-only-via-deferral, for the yellow button tint. */
    public static void markDeferredOnly(IRecipe<?> recipe) {
        deferredOnly.add(recipe.getId());
    }

    public static boolean isDeferredOnly(IRecipe<?> recipe) {
        return recipe != null && deferredOnly.contains(recipe.getId());
    }

    /** A full plan for the click preview, from the same session the outlines used. */
    public static CraftPlanner.PlanOutcome plan(IRecipe<?> recipe, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null ? current.plan(recipe)
                : CraftPlanner.PlanOutcome.of(CraftPlanner.PlanOutcome.Kind.UNSUPPORTED);
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
        sessionStation = null;
        stationMenu = null;
        stationTick = Long.MIN_VALUE;
        stationCounts = Collections.emptyMap();
        stationUsable = false;
        deferredOnly.clear();
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
        if (session == null || generation != inventoryGeneration
                || requestGrid != grid || recipes != recipeManager || station != sessionStation) {
            session = CraftPlanner.session(recipes, player.inventory, requestGrid, station);
            inventoryGeneration = generation;
            grid = requestGrid;
            recipeManager = recipes;
            sessionStation = station;
            deferredOnly.clear();
        }
        return session;
    }
}
