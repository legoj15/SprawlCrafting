package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.craft.solver.CountFormat;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * Builds the deferred-craft plan preview (a header, per-intermediate-item totals, and a confirm hint)
 * from a planned {@link CraftJob}. Called on the client, which has the full RecipeManager on 1.16.5.
 */
public final class CraftPreview {

    private CraftPreview() {
    }

    public static List<ITextComponent> lines(CraftJob job, RecipeManager recipes) {
        List<ITextComponent> out = new ArrayList<>();
        out.add(new TranslationTextComponent("sprawlcrafting.preview.header",
                job.targetResult().getHoverName()).withStyle(TextFormatting.YELLOW));
        // Aggregate intermediate output per item (a chain may revisit the same recipe in non-adjacent
        // steps); the final step is the target itself — the header covers it.
        // long totals: a batched step can be thousands of crafts, so count x crafts overflows int.
        Map<Item, Long> totals = new LinkedHashMap<>();
        Map<Item, ITextComponent> names = new HashMap<>();
        for (CraftStep step : job.steps().subList(0, job.steps().size() - 1)) {
            IRecipe<?> recipe = RecipeIds.byId(recipes, step.recipeId()).orElse(null);
            ItemStack produced = recipe == null ? ItemStack.EMPTY : RecipeIds.resultOf(recipe);
            if (!produced.isEmpty()) {
                totals.merge(produced.getItem(), (long) produced.getCount() * step.crafts(), CraftJob::saturatingAdd);
                names.putIfAbsent(produced.getItem(), produced.getHoverName());
            }
        }
        for (Map.Entry<Item, Long> e : totals.entrySet()) {
            out.add(new TranslationTextComponent("sprawlcrafting.preview.step",
                    CountFormat.format(e.getValue()), names.get(e.getKey())).withStyle(TextFormatting.GRAY));
        }
        out.add(new TranslationTextComponent("sprawlcrafting.preview.confirm")
                .withStyle(TextFormatting.GREEN, TextFormatting.ITALIC));
        return out;
    }
}
