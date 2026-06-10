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
 * Client-side, render-thread-only cache answering "is this recipe craftable from raw
 * resources right now?" for the recipe book (yellow outlines, the craftable filter, and
 * click previews). Wraps a {@link CraftPlanner.Session} over the client's synced
 * RecipeManager and inventory mirror, rebuilt whenever the inventory generation
 * ({@code Inventory.getTimesChanged()}) or grid context changes — the same invalidation
 * signal the vanilla recipe book uses.
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
        return width >= 3 && height >= 3 ? GridContext.CRAFTING_TABLE : GridContext.INVENTORY;
    }

    public static boolean isSolvable(RecipeHolder<?> holder, GridContext requestGrid) {
        CraftPlanner.Session current = currentSession(requestGrid);
        return current != null && current.isSolvable(holder);
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
}
