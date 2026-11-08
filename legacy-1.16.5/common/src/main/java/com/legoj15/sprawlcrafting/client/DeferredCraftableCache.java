package com.legoj15.sprawlcrafting.client;

import java.util.HashSet;
import java.util.Set;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.util.ResourceLocation;

/**
 * Client-side answer to "is this recipe craftable from raw resources right now?" for the recipe book
 * (yellow outlines, the craftable filter, click previews) and the JEI transfer button. 1.16.5 clients
 * receive every recipe, so this runs a {@link CraftPlanner.Session} over the synced RecipeManager and
 * the inventory mirror with no server round trip.
 *
 * <p>Offers are suppressed entirely when the server lacks the mod ({@code serverHasMod}): a start packet
 * could not be delivered there, so showing yellow would be a lie.
 */
public final class DeferredCraftableCache {

    private static CraftPlanner.Session session;
    private static int inventoryGeneration = -1;
    private static GridContext grid;
    private static RecipeManager recipeManager;
    /** Recipes we marked craftable that vanilla did NOT — these get the yellow outline. */
    private static final Set<ResourceLocation> deferredOnly = new HashSet<>();

    private DeferredCraftableCache() {
    }

    /** Grid context for the recipe book pass, from the canCraft width/height arguments. */
    public static GridContext gridFor(int width, int height) {
        return GridContext.forSize(width, height);
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
        if (session == null || generation != inventoryGeneration
                || requestGrid != grid || recipes != recipeManager) {
            session = CraftPlanner.session(recipes, player.inventory, requestGrid);
            inventoryGeneration = generation;
            grid = requestGrid;
            recipeManager = recipes;
            deferredOnly.clear();
        }
        return session;
    }
}
