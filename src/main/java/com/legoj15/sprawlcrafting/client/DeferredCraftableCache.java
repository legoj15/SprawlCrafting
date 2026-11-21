package com.legoj15.sprawlcrafting.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.StationStorages;
import com.legoj15.sprawlcrafting.network.StartProtectedCraftPayload;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Client-side answer to "is this recipe craftable from raw resources right now?" for the recipe
 * book (yellow outlines, the craftable filter, click previews) and the JEI/REI transfer button.
 *
 * <p>1.21.1 computes this on the client by running a {@link CraftPlanner.Session} over the synced
 * RecipeManager and inventory mirror. 26.x (&gt;=1.21.11) clients have no recipe contents, so the
 * server classifies and pushes the deferred-craftable set ({@code DeferredCraftSync} /
 * {@code DeferredCraftableSyncPayload}) and this cache just stores and queries it.
 *
 * <p>Both lines carry a protected tier (issue #40): recipes only the player's protected stacks
 * (enchanted and/or renamed, never damaged) can make. On 1.21.1 a second session that also counts
 * them classifies such recipes (deferred, never direct) whenever the server can honour a protected
 * start; on 26.x the server pushes the protected-only subset
 * ({@code ProtectedCraftableSyncPayload}) right after the deferred set and this cache answers
 * membership. Either way a member's confirm click sends the protected start packet.
 */
public final class DeferredCraftableCache {

    private DeferredCraftableCache() {
    }

    /** Grid context for the recipe book pass, from the canCraft width/height arguments. */
    public static GridContext gridFor(int width, int height) {
        return width >= 3 && height >= 3 ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
    }

    private static AbstractContainerMenu stationMenu;
    private static long stationTick = Long.MIN_VALUE;
    private static Map<Item, Long> stationCounts = Map.of();
    private static boolean stationUsable;
    private static Map<Item, Long> stationProtectedCounts = Map.of();
    private static Map<Item, net.minecraft.world.item.ItemStack> stationProtectedSamples = Map.of();

    /**
     * The plain-item counts of the crafting station the player has open (an AE2 Crafting Terminal's
     * ME network, from its client repo; see {@link StationStorages}), empty for none. Recomputed at
     * most once per client tick (and when the open menu changes): recipe viewers classify many recipes
     * per frame, and the scan is linear in the storage's size. The returned map keeps its identity
     * while the counts are unchanged, so callers can compare by reference to see a change.
     * (Only the 1.21.1 client solves locally; 26.x folds station counts in server-side, in
     * {@code DeferredCraftSync}, since issue #33.)
     */
    public static Map<Item, Long> stationCounts() {
        refreshStation();
        return stationCounts;
    }

    /**
     * The protected-tier counts of the same open station (issue #49): the client mirror of
     * {@code StationStorage#protectedCounts}, cached by the same once-per-tick (or menu-change)
     * refresh and identity-keeping rule as {@link #stationCounts()} — the protected session's rebuild
     * keys off this instance's identity.
     */
    public static Map<Item, Long> stationProtectedCounts() {
        refreshStation();
        return stationProtectedCounts;
    }

    /**
     * The count-1 samples behind {@link #stationProtectedCounts()} (issue #49), same cache and
     * identity rule; they feed the confirmation warning lines.
     */
    public static Map<Item, net.minecraft.world.item.ItemStack> stationProtectedSamples() {
        refreshStation();
        return stationProtectedSamples;
    }

    /** The one refresh behind every station view above: at most one scan per client tick per menu. */
    private static void refreshStation() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            stationUsable = false;
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        long tick = minecraft.level.getGameTime();
        if (menu != stationMenu || tick != stationTick) {
            Map<Item, Long> counts = StationStorages.clientCounts(menu);
            stationUsable = counts != null;
            Map<Item, Long> next = counts == null ? Map.of() : counts;
            if (!next.equals(stationCounts)) {
                stationCounts = next;
            }
            Map<Item, Long> protectedCounts = StationStorages.clientProtectedCounts(menu);
            Map<Item, Long> nextProtected = protectedCounts == null ? Map.of() : protectedCounts;
            if (!nextProtected.equals(stationProtectedCounts)) {
                stationProtectedCounts = nextProtected;
            }
            Map<Item, net.minecraft.world.item.ItemStack> samples = StationStorages.clientProtectedSamples(menu);
            Map<Item, net.minecraft.world.item.ItemStack> nextSamples = samples == null ? Map.of() : samples;
            if (!nextSamples.equals(stationProtectedSamples)) {
                stationProtectedSamples = nextSamples;
            }
            stationMenu = menu;
            stationTick = tick;
        }
    }

    /**
     * Whether the open menu is a usable crafting station (an offline AE2 terminal is not), from the same
     * once-per-tick cache as {@link #stationCounts()}: recipe-viewer handlers ask this per evaluation.
     */
    public static boolean stationUsable() {
        stationCounts();
        return stationUsable;
    }

    //? if >=1.21.11 {
    /*// 26.x: read the server-synced deferred-craftable sets; no client-side solving.
    // displayIds key the recipe book (RecipeDisplayId.index); recipeIds key JEI (recipe identifier).
    private static java.util.Set<Integer> deferredDisplayIds = java.util.Set.of();
    private static java.util.Set<ResourceLocation> deferredRecipeIds = java.util.Set.of();
    // The protected-only subset (issue #40): recipes the plain pool cannot make but the player's
    // protected stacks can, pushed right after the deferred set. Members are deferred (yellow) for
    // outlining, and their confirm click sends the protected start.
    private static java.util.Set<Integer> protectedDisplayIds = java.util.Set.of();
    private static java.util.Set<ResourceLocation> protectedRecipeIds = java.util.Set.of();

    public static void accept(java.util.Set<Integer> displayIds, java.util.Set<ResourceLocation> recipeIds) {
        if (displayIds.equals(deferredDisplayIds) && recipeIds.equals(deferredRecipeIds)) {
            return; // unchanged — whatever pass last ran already reflects these sets
        }
        deferredDisplayIds = displayIds;
        deferredRecipeIds = recipeIds;
        repaintRecipeBook();
    }

    public static void acceptProtected(java.util.Set<Integer> displayIds, java.util.Set<ResourceLocation> recipeIds) {
        if (displayIds.equals(protectedDisplayIds) && recipeIds.equals(protectedRecipeIds)) {
            return; // unchanged — whatever pass last ran already reflects these sets
        }
        protectedDisplayIds = displayIds;
        protectedRecipeIds = recipeIds;
        repaintRecipeBook();
    }

    // The recipe book bakes craftability into per-collection sets during its selectRecipes pass
    // (screen init / inventory-change tick / recipe-book packets) — and these payloads always land
    // AFTER that pass, because the server classifies at end-of-tick. Re-trigger the pass the same
    // way vanilla's ClientPacketListener.refreshRecipeBook does, so the yellow outlines repaint
    // without waiting for a screen re-init.
    private static void repaintRecipeBook() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener listener) {
            listener.recipesUpdated();
        }
    }

    public static boolean isDeferredOnly(net.minecraft.world.item.crafting.display.RecipeDisplayId id) {
        return deferredDisplayIds.contains(id.index()) || protectedDisplayIds.contains(id.index());
    }

    // JEI hands us the RecipeHolder, so we key by identifier. We only distinguish DEFERRED
    // (server said so) from not-our-business; UNSOLVABLE stands in for the latter.
    public static CraftPlanner.Craftability classify(RecipeHolder<?> holder, GridContext requestGrid) {
        return deferredRecipeIds.contains(holder.id().identifier())
                || protectedRecipeIds.contains(holder.id().identifier())
                ? CraftPlanner.Craftability.DEFERRED : CraftPlanner.Craftability.UNSOLVABLE;
    }

    // Whether {@code id} is makeable only by consuming protected stacks (issue #40) — the key the
    // confirm click tests to pick the protected start packet.
    public static boolean isProtectedOnly(net.minecraft.world.item.crafting.display.RecipeDisplayId id) {
        return protectedDisplayIds.contains(id.index());
    }

    // JEI's RecipeHolder form of the same membership (see classify).
    public static boolean isProtectedOnly(RecipeHolder<?> holder) {
        return protectedRecipeIds.contains(holder.id().identifier());
    }

    public static void invalidate() {
        deferredDisplayIds = java.util.Set.of();
        deferredRecipeIds = java.util.Set.of();
        protectedDisplayIds = java.util.Set.of();
        protectedRecipeIds = java.util.Set.of();
    }*/
    //?} else {
    private static CraftPlanner.Session session;
    /** The same pool plus the player's protected stacks (issue #40); null unless protected planning is possible. */
    private static CraftPlanner.Session protectedSession;
    private static int inventoryGeneration = -1;
    private static GridContext grid;
    private static RecipeManager recipeManager;
    /** The {@link #stationCounts()} instance the sessions were built with (compared by reference). */
    private static Map<Item, Long> sessionStation;
    /** The {@link #stationProtectedCounts()} instance ditto (issue #49). */
    private static Map<Item, Long> sessionStationProtected;
    /** Recipes we marked craftable that vanilla did NOT — these get the yellow outline. */
    private static final Set<ResourceLocation> deferredOnly = new HashSet<>();
    /** Of those, the recipes only a protected-including plan can make (issue #40). */
    private static final Set<ResourceLocation> protectedOnly = new HashSet<>();

    public static boolean isSolvable(RecipeHolder<?> holder, GridContext requestGrid) {
        return classify(holder, requestGrid) != CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Direct vs deferred vs unsolvable, for recipe-viewer (JEI/REI) button states. */
    public static CraftPlanner.Craftability classify(RecipeHolder<?> holder, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        if (current == null) {
            return CraftPlanner.Craftability.UNSOLVABLE;
        }
        CraftPlanner.Craftability plain = current.classify(holder);
        if (plain != CraftPlanner.Craftability.UNSOLVABLE || protectedSession == null) {
            return plain;
        }
        if (protectedSession.classify(holder) != CraftPlanner.Craftability.UNSOLVABLE) {
            // Makeable only by consuming protected stacks: deferred, never direct — a one-step craft
            // of that kind must run through our executor too (vanilla's own recipe-book placement
            // refuses enchanted/renamed stacks) — and remembered so the confirm sends the protected
            // start instead of the plain one.
            protectedOnly.add(holder.id());
            return CraftPlanner.Craftability.DEFERRED;
        }
        return CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Marks a recipe as craftable-only-via-deferral, for the yellow button tint. */
    public static void markDeferredOnly(RecipeHolder<?> holder) {
        deferredOnly.add(holder.id());
    }

    public static boolean isDeferredOnly(RecipeHolder<?> holder) {
        return deferredOnly.contains(holder.id());
    }

    /** Whether {@code holder} is makeable only by consuming protected stacks (see {@link #classify}). */
    public static boolean isProtectedOnly(RecipeHolder<?> holder) {
        return protectedOnly.contains(holder.id());
    }

    public static Set<ResourceLocation> deferredOnlyView() {
        return Collections.unmodifiableSet(deferredOnly);
    }

    /**
     * A full plan for the click preview, from the same session the outlines used. A recipe only the
     * protected session can make gets that plan, with {@code usesProtected} set — the preview's
     * warning lines and the confirm click both key off it.
     */
    public static CraftPlanner.PlanOutcome plan(RecipeHolder<?> holder, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        if (current == null) {
            return new CraftPlanner.PlanOutcome.Unsupported();
        }
        CraftPlanner.PlanOutcome outcome = current.plan(holder);
        if (outcome instanceof CraftPlanner.PlanOutcome.Planned || protectedSession == null) {
            return outcome;
        }
        CraftPlanner.PlanOutcome withProtected = protectedSession.plan(holder);
        if (withProtected instanceof CraftPlanner.PlanOutcome.Planned planned) {
            planned.job().setUsesProtected(true);
            return withProtected;
        }
        return outcome; // the plain rejection, with its own message semantics
    }

    /**
     * Drops the session outright — needed on datapack reload, where the client
     * RecipeManager is mutated in place and identity comparison cannot see the change.
     * The planner's producer index is keyed on that same unchanged identity, so it must
     * be evicted too, or the rebuilt session would solve against the pre-reload recipes.
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
        stationCounts = Map.of();
        stationUsable = false;
        stationProtectedCounts = Map.of();
        stationProtectedSamples = Map.of();
        deferredOnly.clear();
        protectedOnly.clear();
    }

    private static CraftPlanner.Session currentSession(GridContext requestGrid) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        int generation = player.getInventory().getTimesChanged();
        RecipeManager recipes = minecraft.level.getRecipeManager();
        // An open crafting station's storage (AE2 Crafting Terminal) joins the pool, as on the server.
        Map<Item, Long> station = stationCounts();
        Map<Item, Long> stationProtected = stationProtectedCounts();
        if (session == null || generation != inventoryGeneration
                || requestGrid != grid || recipes != recipeManager || station != sessionStation
                || stationProtected != sessionStationProtected) {
            session = CraftPlanner.session(recipes, minecraft.level.registryAccess(),
                    player.getInventory(), requestGrid, station);
            // The second pass over the same pool plus the protected stacks (issue #40), built only
            // when the server can honour a protected start and there is any protected stock to count
            // — the player's (issue #40) or the open station's (issue #49): everything else keeps
            // the plain answer. Any change that flips either (an inventory generation, a new
            // connection, the station's protected map) also invalidates this whole block, so it
            // cannot go stale.
            protectedSession = minecraft.getConnection() != null
                    && Services.PLATFORM.canSendToServer(minecraft.getConnection().getConnection(),
                            StartProtectedCraftPayload.ID)
                    && (CraftPlanner.hasProtected(player.getInventory()) || !stationProtected.isEmpty())
                    ? CraftPlanner.session(recipes, minecraft.level.registryAccess(),
                            player.getInventory(), requestGrid, station, stationProtected, true)
                    : null;
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
    //?}
}
