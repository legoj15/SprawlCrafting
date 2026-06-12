package com.legoj15.sprawlcrafting.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
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
 */
public final class DeferredCraftableCache {

    private DeferredCraftableCache() {
    }

    /** Grid context for the recipe book pass, from the canCraft width/height arguments. */
    public static GridContext gridFor(int width, int height) {
        return width >= 3 && height >= 3 ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
    }

    //? if >=1.21.11 {
    /*// 26.x: read the server-synced deferred-craftable sets; no client-side solving.
    // displayIds key the recipe book (RecipeDisplayId.index); recipeIds key JEI (recipe identifier).
    private static java.util.Set<Integer> deferredDisplayIds = java.util.Set.of();
    private static java.util.Set<ResourceLocation> deferredRecipeIds = java.util.Set.of();

    public static void accept(java.util.Set<Integer> displayIds, java.util.Set<ResourceLocation> recipeIds) {
        if (displayIds.equals(deferredDisplayIds) && recipeIds.equals(deferredRecipeIds)) {
            return; // unchanged — whatever pass last ran already reflects these sets
        }
        deferredDisplayIds = displayIds;
        deferredRecipeIds = recipeIds;
        // The recipe book bakes craftability into per-collection sets during its selectRecipes
        // pass (screen init / inventory-change tick / recipe-book packets) — and this payload
        // always lands AFTER that pass, because the server classifies at end-of-tick. Re-trigger
        // the pass the same way vanilla's ClientPacketListener.refreshRecipeBook does, so the
        // yellow outlines repaint without waiting for a screen re-init.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener listener) {
            listener.recipesUpdated();
        }
    }

    public static boolean isDeferredOnly(net.minecraft.world.item.crafting.display.RecipeDisplayId id) {
        return deferredDisplayIds.contains(id.index());
    }

    // JEI hands us the RecipeHolder, so we key by identifier. We only distinguish DEFERRED
    // (server said so) from not-our-business; UNSOLVABLE stands in for the latter.
    public static CraftPlanner.Craftability classify(RecipeHolder<?> holder, GridContext requestGrid) {
        return deferredRecipeIds.contains(holder.id().identifier())
                ? CraftPlanner.Craftability.DEFERRED : CraftPlanner.Craftability.UNSOLVABLE;
    }

    public static void invalidate() {
        deferredDisplayIds = java.util.Set.of();
        deferredRecipeIds = java.util.Set.of();
    }*/
    //?} else {
    private static CraftPlanner.Session session;
    private static int inventoryGeneration = -1;
    private static GridContext grid;
    private static RecipeManager recipeManager;
    /** Recipes we marked craftable that vanilla did NOT — these get the yellow outline. */
    private static final Set<ResourceLocation> deferredOnly = new HashSet<>();

    public static boolean isSolvable(RecipeHolder<?> holder, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null && current.isSolvable(holder);
    }

    /** Direct vs deferred vs unsolvable, for recipe-viewer (JEI/REI) button states. */
    public static CraftPlanner.Craftability classify(RecipeHolder<?> holder, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null ? current.classify(holder) : CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Marks a recipe as craftable-only-via-deferral, for the yellow button tint. */
    public static void markDeferredOnly(RecipeHolder<?> holder) {
        deferredOnly.add(holder.id());
    }

    public static boolean isDeferredOnly(RecipeHolder<?> holder) {
        return deferredOnly.contains(holder.id());
    }

    public static Set<ResourceLocation> deferredOnlyView() {
        return Collections.unmodifiableSet(deferredOnly);
    }

    /** A full plan for the click preview, from the same session the outlines used. */
    public static CraftPlanner.PlanOutcome plan(RecipeHolder<?> holder, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null ? current.plan(holder) : new CraftPlanner.PlanOutcome.Unsupported();
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
        deferredOnly.clear();
    }

    private static CraftPlanner.Session currentSession(GridContext requestGrid) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        int generation = player.getInventory().getTimesChanged();
        RecipeManager recipes = minecraft.level.getRecipeManager();
        if (session == null || generation != inventoryGeneration
                || requestGrid != grid || recipes != recipeManager) {
            session = CraftPlanner.session(recipes, minecraft.level.registryAccess(),
                    player.getInventory(), requestGrid);
            inventoryGeneration = generation;
            grid = requestGrid;
            recipeManager = recipes;
            deferredOnly.clear();
        }
        return session;
    }
    //?}
}
