package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.GatherCandidate;
import com.legoj15.sprawlcrafting.client.RecipeBookGhost;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.client.gui.recipebook.GhostRecipe;
import net.minecraft.client.gui.recipebook.RecipeBookGui;
import net.minecraft.client.gui.recipebook.RecipeBookPage;
import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.gui.recipebook.RecipeWidget;
import net.minecraft.inventory.container.RecipeBookContainer;
import net.minecraft.item.crafting.IRecipe;

/**
 * Recipe-book component (Mojmap {@code RecipeBookComponent}, Forge-1.16.5 {@code RecipeBookGui}): the
 * preview-then-confirm flow for yellow recipes. Intercepts the click right before vanilla sends the
 * place-recipe packet, gives yellow recipes a plan preview on first click and a start packet on the
 * second, opens the gather list on a right-click of a red recipe, and clears stale state when the book
 * (re)binds or recipes reload.
 */
@Mixin(RecipeBookGui.class)
public abstract class RecipeBookGuiMixin {

    private static final int MOUSE_BUTTON_RIGHT = 1;

    @Shadow
    @Final
    private RecipeBookPage recipeBookPage;

    @Shadow
    protected RecipeBookContainer<?> menu;

    @Shadow
    @Final
    protected GhostRecipe ghostRecipe;

    @Shadow
    public abstract boolean isVisible();

    // Clear a stale preview ghost the next time the book draws it after a craft started (flag set by
    // CraftingScreenWatcher), so the ghost never shows over the deferred craft's items.
    @Inject(method = "renderGhostRecipe", at = @At("HEAD"))
    private void sprawlcrafting$clearStaleGhost(CallbackInfo ci) {
        if (RecipeBookGhost.consumeClear()) {
            this.ghostRecipe.clear();
        }
    }

    @Inject(method = "mouseClicked(DDI)Z", cancellable = true,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/PlayerController;handlePlaceRecipe(ILnet/minecraft/item/crafting/IRecipe;Z)V"))
    private void sprawlcrafting$interceptDeferredClick(double mouseX, double mouseY, int button,
                                                       CallbackInfoReturnable<Boolean> cir) {
        RecipeList collection = recipeBookPage.getLastClickedRecipeCollection();
        // Second click on the button that owns the pending preview confirms THAT recipe, regardless of
        // which variant a grouped button's animation is currently showing.
        if (collection != null && DeferredClickState.pendingFor(collection.getRecipes(false)) != null) {
            DeferredClickState.confirmPending();
            cir.setReturnValue(true);
            return;
        }
        IRecipe<?> clicked = recipeBookPage.getLastClickedRecipe();
        if (clicked == null || !DeferredCraftableCache.isDeferredOnly(clicked)) {
            DeferredClickState.clear(); // vanilla click: drop any pending preview
            return; // truly craftable (or unknown): vanilla places it into the grid
        }
        GridContext grid = DeferredCraftableCache.gridFor(menu.getGridWidth(), menu.getGridHeight());
        DeferredClickState.openPreview(clicked, grid);
        // Show the final recipe's layout as a grid ghost (vanilla cleared the previous ghost just
        // before this injection point) so the player sees what the last intermediates feed into.
        ((RecipeBookGui) (Object) this).setupGhostRecipe(clicked, menu.slots);
        cir.setReturnValue(true);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void sprawlcrafting$clearPreviewOnInit(CallbackInfo ci) {
        DeferredClickState.clear();
    }

    // Right-click a red (unmakeable) recipe to open its gather list. HEAD so it runs before vanilla's own
    // handling; the hovered button decides if it's actually red and opens.
    @Inject(method = "mouseClicked(DDI)Z", cancellable = true, at = @At("HEAD"))
    private void sprawlcrafting$interceptGatherClick(double mouseX, double mouseY, int button,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (button != MOUSE_BUTTON_RIGHT || !isVisible()) {
            return;
        }
        if (!SprawlConfig.get().needsSystem()) {
            return; // gather/"needs" helper disabled: right-click stays vanilla
        }
        RecipeBookPageAccessor page = (RecipeBookPageAccessor) recipeBookPage;
        if (page.sprawlcrafting$overlay().isVisible()) {
            return; // variant overlay open: its right-clicks belong to RecipeOverlayGuiMixin
        }
        RecipeWidget hovered = page.sprawlcrafting$hoveredButton();
        if (hovered instanceof GatherCandidate && ((GatherCandidate) hovered).sprawlcrafting$tryOpenGatherList()) {
            cir.setReturnValue(true);
        }
    }

    // HEAD, not TAIL: drop the stale session/marks BEFORE vanilla's update re-runs the craftable pass.
    // A datapack reload mutates the client RecipeManager in place, which identity checks cannot see.
    @Inject(method = "recipesUpdated", at = @At("HEAD"))
    private void sprawlcrafting$invalidateOnRecipesUpdated(CallbackInfo ci) {
        DeferredCraftableCache.invalidate();
        DeferredClickState.clear();
    }
}
