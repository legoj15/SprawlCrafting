package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.crafting.IRecipe;

/**
 * The recipe book's "deferred craftable" (yellow) look, shared by every surface that shows it: the
 * page buttons ({@code GuiButtonRecipeMixin}), the alternates bubble
 * ({@code GuiRecipeOverlayButtonMixin}) and the craftable bucket of a recipe list
 * ({@code RecipeListMixin}). Lives outside the mixin package because classes there cannot be
 * referenced from other code. Every query is solver-cached ({@link ClientPlanCache}) and swallows
 * failures, since it runs on the render path.
 */
public final class RecipeBookHighlight {

    private static final int OUTLINE_COLOR = 0xFFE6D24D;

    private RecipeBookHighlight() {
    }

    /** True if the player cannot craft {@code recipe} directly but can via intermediates. */
    public static boolean isDeferred(IRecipe recipe) {
        return isDeferred(session(), recipe);
    }

    /**
     * The current client plan session, or null without a player. Callers that classify a whole
     * recipe list fetch it once: each {@link ClientPlanCache#get} hashes the inventory.
     */
    public static CraftPlanner.Session session() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null) {
                return null;
            }
            return ClientPlanCache.get(mc.player, GridContext.current(mc.player));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@link #isDeferred(IRecipe)} against an already-fetched session (null means "no"). */
    public static boolean isDeferred(CraftPlanner.Session session, IRecipe recipe) {
        try {
            if (session == null || recipe == null || recipe.getRegistryName() == null) {
                return false;
            }
            return session.classify(recipe) == CraftPlanner.Craftability.DEFERRED;
        } catch (Throwable ignored) {
            // Never let recipe classification break the recipe-book render.
            return false;
        }
    }

    /** Strokes the yellow one-pixel outline around a button's bounds. */
    public static void drawOutline(int x, int y, int width, int height) {
        GlStateManager.disableLighting();
        Gui.drawRect(x, y, x + width, y + 1, OUTLINE_COLOR);
        Gui.drawRect(x, y + height - 1, x + width, y + height, OUTLINE_COLOR);
        Gui.drawRect(x, y, x + 1, y + height, OUTLINE_COLOR);
        Gui.drawRect(x + width - 1, y, x + width, y + height, OUTLINE_COLOR);
        // Gui.drawRect leaves the GL colour set to OUTLINE_COLOR; vanilla draws the NEXT button's
        // frame texture assuming white, so restore it or the next frame renders yellow-tinted.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
