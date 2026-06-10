package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.CraftJob;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.PlanOutcome;
import com.legoj15.sprawlcrafting.craft.CraftStep;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Preview-then-confirm state for yellow recipe book clicks (render thread only).
 * First click on a deferred-craftable recipe computes the plan client-side and shows it;
 * a second click on the same recipe (with the inventory unchanged) sends the start
 * packet — the server re-plans authoritatively.
 */
public final class DeferredClickState {

    private static RecipeHolder<?> pending;
    private static int pendingInventoryGeneration = -1;
    private static List<Component> previewLines = List.of();

    private DeferredClickState() {
    }

    /**
     * Opens a plan preview for a freshly-clicked deferred-craftable recipe.
     * Confirmation is handled separately via {@link #confirmPending()} so that on a
     * grouped (multi-recipe) button the second click confirms the recipe that was
     * previewed, not whichever variant the icon animation has since cycled to.
     */
    public static void openPreview(RecipeHolder<?> holder, GridContext grid) {
        preview(holder, grid, Minecraft.getInstance().player.getInventory().getTimesChanged());
    }

    /**
     * The pending preview recipe if it belongs to {@code collectionRecipes} and is still
     * valid (inventory unchanged) — i.e. a second click on that button should confirm it.
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
     * Preview lines for a button whose collection contains the pending recipe, or empty.
     * Keyed on collection membership, not the cycled display recipe, so the "Click again
     * to start" instruction stays visible while a grouped button's icon animates.
     */
    public static List<Component> previewLinesFor(List<RecipeHolder<?>> collectionRecipes) {
        return pendingFor(collectionRecipes) != null ? previewLines : List.of();
    }

    public static void clear() {
        pending = null;
        previewLines = List.of();
    }

    /** Fires the start request; shared with the JEI transfer handler. */
    public static void sendStartPacket(RecipeHolder<?> holder) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            // Vanilla send path — identical to what both loaders' helpers do internally.
            connection.send(new ServerboundCustomPayloadPacket(new StartDeferredCraftPayload(holder.id())));
        }
    }

    private static void preview(RecipeHolder<?> holder, GridContext grid, int generation) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(DeferredCraftableCache.plan(holder, grid) instanceof PlanOutcome.Planned planned)) {
            // Inventory changed since the outline was computed; treat as not startable.
            clear();
            return;
        }
        CraftJob job = planned.job();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("sprawlcrafting.preview.header",
                job.targetResult().getHoverName()).withStyle(ChatFormatting.YELLOW));
        // Aggregate intermediate output per item (a chain may revisit the same recipe in
        // non-adjacent steps); the final step is the target itself — the header covers it.
        Map<Item, Integer> totals = new LinkedHashMap<>();
        Map<Item, Component> names = new HashMap<>();
        for (CraftStep step : job.steps().subList(0, job.steps().size() - 1)) {
            ItemStack produced = minecraft.level.getRecipeManager().byKey(step.recipeId())
                    .map(h -> h.value().getResultItem(minecraft.level.registryAccess()))
                    .orElse(ItemStack.EMPTY);
            if (!produced.isEmpty()) {
                totals.merge(produced.getItem(), produced.getCount() * step.crafts(), Integer::sum);
                names.putIfAbsent(produced.getItem(), produced.getHoverName());
            }
        }
        totals.forEach((item, count) -> lines.add(Component.translatable("sprawlcrafting.preview.step",
                count, names.get(item)).withStyle(ChatFormatting.GRAY)));
        lines.add(Component.translatable("sprawlcrafting.preview.confirm")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        pending = holder;
        pendingInventoryGeneration = generation;
        previewLines = lines;
    }
}
