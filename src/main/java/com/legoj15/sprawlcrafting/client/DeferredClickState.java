package com.legoj15.sprawlcrafting.client;

import java.util.List;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.CraftPreview;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.RecipeIds;
import com.legoj15.sprawlcrafting.network.RequestCraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftByDisplayPayload;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Preview-then-confirm state for yellow recipe book clicks (render thread only). First click on a
 * deferred-craftable recipe shows its plan; a second click on the same recipe (inventory unchanged)
 * sends the start packet and the server re-plans authoritatively.
 *
 * <p>1.21.1 keys the pending recipe by {@code RecipeHolder} and builds the preview client-side.
 * 26.x keys it by the recipe book's opaque {@code RecipeDisplayId} and gets the preview from the
 * server (it cannot plan locally); confirm sends the display-id start payload.
 */
public final class DeferredClickState {

    private static int pendingInventoryGeneration = -1;
    private static List<Component> previewLines = List.of();

    private DeferredClickState() {
    }

    /**
     * Fires the start request for a recipe we hold directly (the JEI/REI transfer button, both
     * versions — those viewers supply the recipe, so the identifier path always works).
     */
    public static void sendStartPacket(RecipeHolder<?> holder) {
        // Routed through the platform helper: the raw vanilla send works on NeoForge but not on
        // Fabric, so each loader uses its own C2S networking API.
        Services.PLATFORM.sendToServer(new StartDeferredCraftPayload(RecipeIds.id(holder)));
    }

    //? if >=1.21.11 {
    /*// 26.x: pending keyed by RecipeDisplayId; the preview breakdown is planned server-side.
    private static net.minecraft.world.item.crafting.display.RecipeDisplayId pendingDisplay;

    public static void openPreview(net.minecraft.world.item.crafting.display.RecipeDisplayId id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        pendingDisplay = id;
        pendingInventoryGeneration = minecraft.player.getInventory().getTimesChanged();
        previewLines = List.of();
        Services.PLATFORM.sendToServer(new RequestCraftPreviewPayload(id.index()));
    }

    // Server reply to the first-click preview request: store the lines if still pending + current.
    public static void acceptPreview(int displayId, List<Component> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pendingDisplay == null || minecraft.player == null
                || minecraft.player.getInventory().getTimesChanged() != pendingInventoryGeneration
                || pendingDisplay.index() != displayId) {
            return;
        }
        if (lines.isEmpty()) {
            clear(); // server says no longer plannable (inventory changed)
            return;
        }
        previewLines = lines;
    }

    public static net.minecraft.world.item.crafting.display.RecipeDisplayId pendingFor(
            List<net.minecraft.world.item.crafting.display.RecipeDisplayId> collectionIds) {
        if (pendingDisplay == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.player.getInventory().getTimesChanged() != pendingInventoryGeneration) {
            clear();
            return null;
        }
        return collectionIds.contains(pendingDisplay) ? pendingDisplay : null;
    }

    public static void confirmPending() {
        if (pendingDisplay != null) {
            Services.PLATFORM.sendToServer(new StartDeferredCraftByDisplayPayload(pendingDisplay.index()));
            clear();
        }
    }

    public static List<Component> previewLinesFor(
            List<net.minecraft.world.item.crafting.display.RecipeDisplayId> collectionIds) {
        return pendingFor(collectionIds) != null ? previewLines : List.of();
    }

    public static void clear() {
        pendingDisplay = null;
        previewLines = List.of();
    }*/
    //?} else {
    private static RecipeHolder<?> pending;

    /**
     * Opens a plan preview for a freshly-clicked deferred-craftable recipe. Confirmation is
     * handled separately via {@link #confirmPending()} so that on a grouped (multi-recipe) button
     * the second click confirms the recipe that was previewed, not whichever variant the icon
     * animation has since cycled to.
     */
    public static void openPreview(RecipeHolder<?> holder, GridContext grid) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        int generation = minecraft.player.getInventory().getTimesChanged();
        if (!(DeferredCraftableCache.plan(holder, grid) instanceof CraftPlanner.PlanOutcome.Planned planned)) {
            // Inventory changed since the outline was computed; treat as not startable.
            clear();
            return;
        }
        pending = holder;
        pendingInventoryGeneration = generation;
        previewLines = CraftPreview.lines(planned.job(),
                minecraft.level.getRecipeManager(), minecraft.level.registryAccess());
    }

    /**
     * The pending preview recipe if it belongs to {@code collectionRecipes} and is still valid
     * (inventory unchanged) — i.e. a second click on that button should confirm it.
     */
    public static RecipeHolder<?> pendingFor(List<RecipeHolder<?>> collectionRecipes) {
        if (pending == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.player.getInventory().getTimesChanged() != pendingInventoryGeneration) {
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

    /**
     * Preview lines for a button whose collection contains the pending recipe, or empty. Keyed on
     * collection membership, not the cycled display recipe, so the "Click again to start"
     * instruction stays visible while a grouped button's icon animates.
     */
    public static List<Component> previewLinesFor(List<RecipeHolder<?>> collectionRecipes) {
        return pendingFor(collectionRecipes) != null ? previewLines : List.of();
    }

    public static void clear() {
        pending = null;
        previewLines = List.of();
    }
    //?}
}
