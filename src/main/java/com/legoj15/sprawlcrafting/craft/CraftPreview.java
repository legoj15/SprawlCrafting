package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Builds the deferred-craft plan preview (a header, per-intermediate-item totals, and a confirm
 * hint) from a planned {@link CraftJob}. Cross-version and side-neutral: 1.21.1 calls it on the
 * client (which has the RecipeManager), 26.x calls it on the server and streams the lines down,
 * since the 26.x client cannot resolve a step's recipe to its result.
 */
public final class CraftPreview {

    private CraftPreview() {
    }

    public static List<Component> lines(CraftJob job, RecipeManager recipes, HolderLookup.Provider registries) {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("sprawlcrafting.preview.header",
                job.targetResult().getHoverName()).withStyle(ChatFormatting.YELLOW));
        // Aggregate intermediate output per item (a chain may revisit the same recipe in
        // non-adjacent steps); the final step is the target itself — the header covers it.
        Map<Item, Integer> totals = new LinkedHashMap<>();
        Map<Item, Component> names = new HashMap<>();
        for (CraftStep step : job.steps().subList(0, job.steps().size() - 1)) {
            ItemStack produced = RecipeIds.byId(recipes, step.recipeId())
                    .map(holder -> RecipeIds.resultOf(holder.value(), registries))
                    .orElse(ItemStack.EMPTY);
            if (!produced.isEmpty()) {
                totals.merge(produced.getItem(), produced.getCount() * step.crafts(), Integer::sum);
                names.putIfAbsent(produced.getItem(), produced.getHoverName());
            }
        }
        totals.forEach((item, count) -> out.add(Component.translatable("sprawlcrafting.preview.step",
                count, names.get(item)).withStyle(ChatFormatting.GRAY)));
        out.add(Component.translatable("sprawlcrafting.preview.confirm")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        return out;
    }
}
