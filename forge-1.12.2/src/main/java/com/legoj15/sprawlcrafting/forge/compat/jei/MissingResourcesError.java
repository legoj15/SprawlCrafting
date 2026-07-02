package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.forge.craft.ItemDemand;
import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;

import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

/**
 * The JEI transfer error shown when a crafting recipe can't be (deferred-)crafted from current
 * stock: a {@code USER_FACING} error whose hover tooltip lists the raw materials still needed
 * (computed by {@link ShortfallView}), drawn over JEI's native red highlight on the recipe slots
 * that block the craft. The highlight goes through the public {@link IGuiIngredient#drawHighlight}
 * — the same call and color stock JEI's own missing-items error uses, so it renders exactly like
 * the vanilla-JEI experience (hover-time only; stock behaves the same way). We can't reuse JEI's
 * {@code createUserErrorForSlots} because its tooltip is a fixed two-liner; this error carries the
 * multi-line raw-materials breakdown instead. Raw-only.
 */
public class MissingResourcesError implements IRecipeTransferError {

    private static final int MAX_LINES = 12;
    /** Stock JEI's missing-slot red (verified against RecipeTransferErrorSlots' constant). */
    private static final Color HIGHLIGHT = new Color(1.0F, 0.0F, 0.0F, 0.4F);

    private final ShortfallView shortfall;
    private final List<Integer> missingSlots;
    private List<String> cachedLines;

    public MissingResourcesError(ShortfallView shortfall, List<Integer> missingSlots) {
        this.shortfall = shortfall;
        this.missingSlots = missingSlots == null
                ? Collections.<Integer>emptyList() : missingSlots;
    }

    @Override
    public Type getType() {
        return Type.USER_FACING;
    }

    @Override
    public void showError(Minecraft minecraft, int mouseX, int mouseY,
                          IRecipeLayout recipeLayout, int recipeX, int recipeY) {
        Map<Integer, ? extends IGuiIngredient<ItemStack>> ingredients =
                recipeLayout.getItemStacks().getGuiIngredients();
        for (Integer slot : missingSlots) {
            IGuiIngredient<ItemStack> ingredient = ingredients.get(slot);
            if (ingredient != null) {
                ingredient.drawHighlight(minecraft, HIGHLIGHT, recipeX, recipeY);
            }
        }
        if (minecraft.currentScreen != null) {
            minecraft.currentScreen.drawHoveringText(lines(), mouseX, mouseY);
        }
    }

    private List<String> lines() {
        if (cachedLines != null) {
            return cachedLines;
        }
        List<String> out = new ArrayList<String>();
        out.add(TextFormatting.RED + "Can't sprawl-craft this yet.");
        if (shortfall.isEmpty()) {
            out.add(TextFormatting.GRAY + "Needs a crafting table, or a recipe I can't plan.");
            cachedLines = out;
            return out;
        }
        out.add(TextFormatting.GRAY + "Raw materials still needed:");
        List<ItemDemand> demands = shortfall.demands();
        int shown = 0;
        for (ItemDemand demand : demands) {
            if (shown >= MAX_LINES) {
                out.add(TextFormatting.DARK_GRAY + " +" + (demands.size() - shown) + " more...");
                break;
            }
            String name = demand.representative().toStack().getDisplayName();
            if (demand.items().size() > 1) {
                name = name + TextFormatting.DARK_GRAY + " (or alt.)";
            }
            out.add(TextFormatting.WHITE + "  " + demand.count() + "x " + TextFormatting.GRAY + name);
            shown++;
        }
        if (shortfall.approximate()) {
            out.add(TextFormatting.DARK_GRAY + "(estimate — may undercount)");
        }
        cachedLines = out;
        return out;
    }
}
