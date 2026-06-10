package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.List;

import com.legoj15.sprawlcrafting.craft.CraftJob;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.PlanOutcome;
import com.legoj15.sprawlcrafting.craft.CraftStep;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.network.StartDeferredCraftPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
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
     * Handles a click on a deferred-craftable recipe.
     *
     * @return true if this click CONFIRMED (packet sent), false if it opened a preview
     */
    public static boolean click(RecipeHolder<?> holder, GridContext grid) {
        Minecraft minecraft = Minecraft.getInstance();
        int generation = minecraft.player.getInventory().getTimesChanged();
        if (pending != null && pending.id().equals(holder.id())
                && generation == pendingInventoryGeneration) {
            confirm(holder);
            return true;
        }
        preview(holder, grid, generation);
        return false;
    }

    public static List<Component> previewLines() {
        if (pending == null) {
            return List.of();
        }
        // The preview describes a plan against a specific inventory state; drop it the
        // moment the inventory changes (same generation signal the outlines use).
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.player.getInventory().getTimesChanged() != pendingInventoryGeneration) {
            clear();
            return List.of();
        }
        return previewLines;
    }

    public static void clear() {
        pending = null;
        previewLines = List.of();
    }

    private static void confirm(RecipeHolder<?> holder) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            // Vanilla send path — identical to what both loaders' helpers do internally.
            connection.send(new ServerboundCustomPayloadPacket(new StartDeferredCraftPayload(holder.id())));
        }
        clear();
    }

    private static void preview(RecipeHolder<?> holder, GridContext grid, int generation) {
        Minecraft minecraft = Minecraft.getInstance();
        List<Component> lines = new ArrayList<>();
        if (DeferredCraftableCache.plan(holder, grid) instanceof PlanOutcome.Planned planned) {
            CraftJob job = planned.job();
            lines.add(Component.translatable("sprawlcrafting.preview.header",
                    job.targetResult().getHoverName()).withStyle(ChatFormatting.YELLOW));
            for (CraftStep step : job.steps()) {
                ItemStack produced = minecraft.level.getRecipeManager().byKey(step.recipeId())
                        .map(h -> h.value().getResultItem(minecraft.level.registryAccess()))
                        .orElse(ItemStack.EMPTY);
                lines.add(Component.translatable("sprawlcrafting.preview.step",
                        produced.getCount() * step.crafts(), produced.getHoverName())
                        .withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.translatable("sprawlcrafting.preview.confirm")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
            pending = holder;
            pendingInventoryGeneration = generation;
            previewLines = lines;
        } else {
            // Inventory changed since the outline was computed; treat as not startable.
            clear();
        }
    }
}
