package com.legoj15.sprawlcrafting.forge.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.client.DeferredClickState;
import com.legoj15.sprawlcrafting.forge.client.RecipeBookHighlight;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.recipebook.GuiButtonRecipe;
import net.minecraft.client.gui.recipebook.RecipeList;
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
            RecipeBookHighlight.drawOutline(self.x, self.y, self.width, self.height);
        }
    }

    /**
     * Discoverability, appended to the button's hover tooltip (modern parity): the armed recipe's
     * plan preview ({@link DeferredClickState#previewLines()}) while this exact recipe is armed —
     * part of vanilla's single button tooltip, so it shows only while hovering that button, drawn
     * where vanilla draws it (after the container slots, so not clipped). Otherwise a yellow
     * "craftable from raw materials" line on deferred recipes, and — needs-helper-gated — a gold
     * "click to see what you need" hint on red ones, which is how a player learns the
     * missing-resources screen exists at all. An empty preview (nothing plannable right now) falls
     * through to the plain hints.
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
            if (DeferredClickState.isArmedFor(recipe)) {
                List<String> preview = DeferredClickState.previewLines();
                if (!preview.isEmpty()) {
                    lines.addAll(preview);
                    return;
                }
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

    /**
     * Empty-list guard. {@link RecipeListMixin} lets the craftable-only filter keep a list whose
     * only makeable recipes are deferred; if one stops being deferred between the book's refresh
     * and a draw (a station chest emptying, say) the ordered list is empty and vanilla
     * {@code drawButton} divides by zero ({@code % list.size()}). Fall back to the list's
     * unlocked, fitting recipes (then all of them) so the button shows something until the next
     * refresh instead of crashing the client.
     */
    @Inject(method = "getOrderedRecipes", at = @At("RETURN"), cancellable = true, require = 0)
    private void sprawlcrafting$neverEmpty(CallbackInfoReturnable<List<IRecipe>> cir) {
        try {
            List<IRecipe> ordered = cir.getReturnValue();
            if (ordered != null && !ordered.isEmpty()) {
                return;
            }
            RecipeList list = ((GuiButtonRecipe) (Object) this).getList();
            List<IRecipe> fallback = new ArrayList<>(list.getRecipes(false));
            if (fallback.isEmpty()) {
                fallback.addAll(list.getRecipes());
            }
            if (!fallback.isEmpty()) {
                cir.setReturnValue(fallback);
            }
        } catch (Throwable ignored) {
            // Vanilla result stands.
        }
    }

    /**
     * Variant lock + index guard for the ordered-list {@code List.get(int)} calls in
     * {@code drawButton} (which picks the displayed recipe), {@code getRecipe} and
     * {@code getToolTipText}. A grouped button cycles its displayed recipe every 1.5 s, so the
     * recipe a click resolves could be a different variant than the one previewed; while a recipe
     * from this button's list is armed, always show that one, so its icon and tooltip present the
     * previewed variant and the confirm click (which resolves {@code getRecipe()}) hits it even
     * though the cycle would have moved on. The list holds {@link IRecipe}s, compared by identity.
     * Otherwise both read paths index the list with the {@code currentIndex} the last draw
     * computed; if the list shrank since (a status change processed in the tick before a click),
     * wrap the index instead of throwing.
     */
    @Redirect(
            method = {"drawButton", "getRecipe", "getToolTipText"},
            at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;",
                    remap = false),
            require = 0)
    private Object sprawlcrafting$wrapIndex(List<?> list, int index) {
        IRecipe armed = DeferredClickState.armed();
        if (armed != null && list.contains(armed)) {
            return armed;
        }
        int size = list.size();
        return size == 0 ? list.get(index) : list.get(Math.floorMod(index, size));
    }

    /** True if the recipe this button currently shows is craftable only via intermediates. */
    private boolean isDisplayedRecipeDeferred() {
        try {
            return RecipeBookHighlight.isDeferred(((GuiButtonRecipe) (Object) this).getRecipe());
        } catch (Throwable ignored) {
            // getRecipe() indexes the ordered list; never let it break the recipe-book render.
            return false;
        }
    }
}
