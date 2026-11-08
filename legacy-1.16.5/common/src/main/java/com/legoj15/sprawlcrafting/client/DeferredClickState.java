package com.legoj15.sprawlcrafting.client;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.CraftPreview;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.text.ITextComponent;

/**
 * Preview-then-confirm state for yellow recipe-book clicks (render thread only). First click on a
 * deferred-craftable recipe shows its plan; a second click on the same recipe (inventory unchanged)
 * sends the start packet and the server re-plans authoritatively.
 */
public final class DeferredClickState {

    private static int pendingInventoryGeneration = -1;
    private static List<ITextComponent> previewLines = ImmutableList.of();
    private static IRecipe<?> pending;

    private DeferredClickState() {
    }

    /** Fires the start request for a recipe we hold directly (the JEI transfer button). */
    public static void sendStartPacket(IRecipe<?> recipe) {
        Services.PLATFORM.sendStartCraft(recipe.getId());
    }

    /**
     * Opens a plan preview for a freshly-clicked deferred-craftable recipe. Confirmation is separate
     * ({@link #confirmPending()}) so that on a grouped button the second click confirms the recipe that
     * was previewed, not whichever variant the icon animation has since cycled to.
     */
    public static void openPreview(IRecipe<?> recipe, GridContext grid) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        int generation = minecraft.player.inventory.getTimesChanged();
        CraftPlanner.PlanOutcome outcome = DeferredCraftableCache.plan(recipe, grid);
        if (!outcome.isPlanned()) {
            clear(); // inventory changed since the outline was computed: not startable
            return;
        }
        pending = recipe;
        pendingInventoryGeneration = generation;
        previewLines = CraftPreview.lines(outcome.job(), minecraft.level.getRecipeManager());
    }

    /**
     * The pending preview recipe if it belongs to {@code collectionRecipes} and is still valid
     * (inventory unchanged) — i.e. a second click on that button should confirm it.
     */
    public static IRecipe<?> pendingFor(List<IRecipe<?>> collectionRecipes) {
        if (pending == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.player.inventory.getTimesChanged() != pendingInventoryGeneration) {
            clear();
            return null;
        }
        return collectionRecipes.contains(pending) ? pending : null;
    }

    /** Confirms the pending recipe (sends the start packet) and clears the preview. */
    public static void confirmPending() {
        if (pending != null) {
            sendStartPacket(pending);
            clear();
        }
    }

    /** Preview lines for a button whose collection contains the pending recipe, or empty. */
    public static List<ITextComponent> previewLinesFor(List<IRecipe<?>> collectionRecipes) {
        return pendingFor(collectionRecipes) != null ? previewLines : ImmutableList.<ITextComponent>of();
    }

    public static void clear() {
        pending = null;
        previewLines = ImmutableList.of();
    }
}
