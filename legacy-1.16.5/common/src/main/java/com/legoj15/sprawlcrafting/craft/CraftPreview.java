package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.solver.CountFormat;

import net.minecraft.entity.player.PlayerInventory;
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
 * A job planned with the player's protected stacks (issue #40) also gets the warning lines
 * {@link #protectedWarningLines} adds: which special items the plan will consume.
 */
public final class CraftPreview {

    private CraftPreview() {
    }

    public static List<ITextComponent> lines(CraftJob job, RecipeManager recipes, PlayerInventory inventory) {
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
        out.addAll(protectedWarningLines(job, recipes, inventory));
        out.add(new TranslationTextComponent("sprawlcrafting.preview.confirm")
                .withStyle(TextFormatting.GREEN, TextFormatting.ITALIC));
        return out;
    }

    /**
     * The protected-stack warning (issue #40): a gold header plus one line per protected item the plan
     * will consume, naming why it is special. Empty for a plan that uses none — including a
     * {@code usesProtected} job whose plain copies happened to cover everything, which can happen when
     * the inventory changed between the plan and this look.
     */
    public static List<ITextComponent> protectedWarningLines(CraftJob job, RecipeManager recipes,
                                                             PlayerInventory inventory) {
        if (!job.usesProtected()) {
            return Collections.emptyList();
        }
        List<ItemStack> used = CraftPlanner.protectedItemsUsed(job, recipes, inventory);
        if (used.isEmpty()) {
            return Collections.emptyList();
        }
        List<ITextComponent> out = new ArrayList<>();
        out.add(new TranslationTextComponent("sprawlcrafting.preview.protected_header")
                .withStyle(TextFormatting.GOLD));
        for (ItemStack stack : used) {
            String key;
            if (stack.isEnchanted() && stack.hasCustomHoverName()) {
                key = "sprawlcrafting.preview.protected_both";
            } else if (stack.isEnchanted()) {
                key = "sprawlcrafting.preview.protected_enchanted";
            } else {
                key = "sprawlcrafting.preview.protected_renamed";
            }
            out.add(new TranslationTextComponent(key, stack.getHoverName()).withStyle(TextFormatting.GOLD));
        }
        return out;
    }
}
