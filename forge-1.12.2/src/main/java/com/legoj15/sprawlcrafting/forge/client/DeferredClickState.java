package com.legoj15.sprawlcrafting.forge.client;

import java.util.ArrayList;
import java.util.List;

import com.legoj15.sprawlcrafting.forge.craft.CraftJob;
import com.legoj15.sprawlcrafting.forge.craft.CraftStep;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;

/**
 * Client state for the two-click deferred-craft flow: the first click on a yellow recipe "arms" it
 * and shows a preview of the plan; a second click on the same recipe confirms and starts it. Mirrors
 * the modern recipe-book preview/confirm. A single recipe is armed at a time; clicking a different
 * one re-arms.
 */
public final class DeferredClickState {

    private DeferredClickState() {
    }

    private static volatile ResourceLocation armedRecipe;
    private static volatile CraftJob armedPlan;

    public static void arm(ResourceLocation recipe, CraftJob plan) {
        armedRecipe = recipe;
        armedPlan = plan;
    }

    public static void disarm() {
        armedRecipe = null;
        armedPlan = null;
    }

    public static boolean isArmedFor(ResourceLocation recipe) {
        return recipe != null && recipe.equals(armedRecipe);
    }

    public static boolean isArmed() {
        return armedRecipe != null && armedPlan != null;
    }

    /** Tooltip lines previewing the armed plan: title, the component crafts, and the confirm hint. */
    public static List<String> previewLines() {
        List<String> lines = new ArrayList<String>();
        CraftJob plan = armedPlan;
        if (plan == null) {
            return lines;
        }
        lines.add(TextFormatting.YELLOW + I18n.format("sprawlcrafting.preview.header",
                plan.targetResult().getDisplayName()));
        lines.add(TextFormatting.GRAY + I18n.format("sprawlcrafting.preview.steps",
                plan.totalCrafts()));
        List<CraftStep> steps = plan.steps();
        int shown = 0;
        for (int i = 0; i < steps.size() && shown < 6; i++) {
            CraftStep step = steps.get(i);
            IRecipe recipe = CraftingManager.getRecipe(step.recipeId());
            if (recipe == null) {
                continue;
            }
            ItemStack output = recipe.getRecipeOutput();
            String name = output.isEmpty() ? step.recipeId().toString() : output.getDisplayName();
            int total = step.crafts() * Math.max(1, output.getCount());
            lines.add(TextFormatting.DARK_GRAY + I18n.format("sprawlcrafting.preview.step", total, name));
            shown++;
        }
        if (steps.size() > shown) {
            lines.add(TextFormatting.DARK_GRAY + " ...");
        }
        lines.add(TextFormatting.GREEN + I18n.format("sprawlcrafting.preview.confirm"));
        return lines;
    }
}
