package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.recipebook.GuiButtonRecipe;
import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.crafting.IRecipe;

/**
 * Gives a recipe-book button a clean "deferred craftable" look when the player cannot craft its
 * recipe directly but <em>can</em> make it from raw materials via intermediates — the 1.12.2 analog
 * of the modern yellow highlight (see {@link GuiRecipeBookMixin} for the click).
 *
 * <p>Two cooperating injectors:
 * <ul>
 *   <li>A {@code @Redirect} on the {@code RecipeList.containsCraftableRecipes()} call that chooses
 *       the button frame: a deferred recipe reports "craftable" so vanilla draws its neutral
 *       <em>grey</em> frame instead of the <em>red</em> not-craftable frame. Without this the yellow
 *       outline would sit on top of the red frame (the confusing "red + yellow" look).</li>
 *   <li>An {@code @Inject} at the tail of {@code drawButton} that strokes a yellow outline over a
 *       deferred button — so deferred reads as grey-frame + yellow-border, distinct from both
 *       directly-craftable (grey, no border) and impossible (red).</li>
 * </ul>
 *
 * <p>Both are cached ({@link ClientPlanCache}), wrapped so they can never throw from render, and
 * carry {@code require = 0} so a mapping mismatch degrades to vanilla behaviour rather than crashing.
 */
@Mixin(GuiButtonRecipe.class)
public class GuiButtonRecipeMixin {

    private static final int OUTLINE_COLOR = 0xFFE6D24D;

    /** Frame selection: treat a deferred recipe as craftable so vanilla draws the grey frame. */
    @Redirect(
            method = "drawButton",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/recipebook/RecipeList;containsCraftableRecipes()Z"),
            require = 0)
    private boolean sprawlcrafting$deferredCountsAsCraftable(RecipeList list) {
        return list.containsCraftableRecipes() || isDisplayedRecipeDeferred();
    }

    @Inject(method = "drawButton", at = @At("TAIL"), require = 0)
    private void sprawlcrafting$highlightDeferred(Minecraft mc, int mouseX, int mouseY,
                                                  float partialTicks, CallbackInfo ci) {
        GuiButton self = (GuiButton) (Object) this;
        if (!self.visible) {
            return;
        }
        if (isDisplayedRecipeDeferred()) {
            drawOutline(self.x, self.y, self.width, self.height);
        }
    }

    /**
     * Discoverability, appended to the button's hover tooltip (modern parity): a yellow
     * "craftable from raw materials" line on deferred recipes, and — needs-helper-gated — a gold
     * "click to see what you need" hint on red ones, which is how a player learns the
     * missing-resources screen exists at all.
     */
    @Inject(method = "getToolTipText", at = @At("RETURN"), require = 0)
    private void sprawlcrafting$appendHints(net.minecraft.client.gui.GuiScreen screen,
                                            CallbackInfoReturnable<java.util.List<String>> cir) {
        try {
            java.util.List<String> lines = cir.getReturnValue();
            if (lines == null) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null) {
                return;
            }
            IRecipe recipe = ((GuiButtonRecipe) (Object) this).getRecipe();
            if (recipe == null || recipe.getRegistryName() == null) {
                return;
            }
            GridContext grid = GridContext.current(mc.player);
            CraftPlanner.Craftability craftability =
                    ClientPlanCache.get(mc.player, grid).classify(recipe);
            if (craftability == CraftPlanner.Craftability.DEFERRED) {
                lines.add(net.minecraft.util.text.TextFormatting.YELLOW
                        + net.minecraft.client.resources.I18n.format("sprawlcrafting.recipe.deferred"));
            } else if (craftability == CraftPlanner.Craftability.UNSOLVABLE
                    && com.legoj15.sprawlcrafting.forge.SprawlConfig.needsSystem) {
                lines.add(net.minecraft.util.text.TextFormatting.GOLD
                        + net.minecraft.client.resources.I18n.format("sprawlcrafting.gather.click"));
            }
        } catch (Throwable ignored) {
            // Tooltip decoration must never break the recipe book.
        }
    }

    /** True if the recipe this button currently shows is craftable only via intermediates. */
    private boolean isDisplayedRecipeDeferred() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null) {
                return false;
            }
            IRecipe recipe = ((GuiButtonRecipe) (Object) this).getRecipe();
            if (recipe == null || recipe.getRegistryName() == null) {
                return false;
            }
            GridContext grid = GridContext.current(mc.player);
            return ClientPlanCache.get(mc.player, grid).classify(recipe) == CraftPlanner.Craftability.DEFERRED;
        } catch (Throwable ignored) {
            // Never let recipe classification break the recipe-book render.
            return false;
        }
    }

    private static void drawOutline(int x, int y, int width, int height) {
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
