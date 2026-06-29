package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.util.ArrayList;
import java.util.List;

import com.legoj15.sprawlcrafting.forge.craft.ItemDemand;
import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;

import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextFormatting;

/**
 * The JEI transfer error shown when a crafting recipe can't be (deferred-)crafted from current
 * stock: a {@code USER_FACING} error whose hover tooltip lists the raw materials still needed
 * (computed by {@link ShortfallView}). This is how a player sees the missing ingredients for a JEI
 * recipe — JEI greys a button with a user error and calls {@link #showError} on hover. Raw-only.
 */
public class MissingResourcesError implements IRecipeTransferError {

    private static final int MAX_LINES = 12;

    private final ShortfallView shortfall;
    private List<String> cachedLines;

    public MissingResourcesError(ShortfallView shortfall) {
        this.shortfall = shortfall;
    }

    @Override
    public Type getType() {
        return Type.USER_FACING;
    }

    @Override
    public void showError(Minecraft minecraft, int mouseX, int mouseY,
                          IRecipeLayout recipeLayout, int recipeX, int recipeY) {
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
