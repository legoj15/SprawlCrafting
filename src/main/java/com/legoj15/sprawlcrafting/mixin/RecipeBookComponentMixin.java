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

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
//? if >=1.21.11 {
/*import net.minecraft.client.gui.screens.recipebook.GhostSlots;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;*/
//?} else {
import com.legoj15.sprawlcrafting.craft.GridContext;
import net.minecraft.client.gui.screens.recipebook.GhostRecipe;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
//?}

/**
 * The preview-then-confirm flow for yellow (deferred-craftable) recipes: intercepts clicks right
 * before vanilla sends the place-recipe packet, gives yellow recipes a plan preview on first click
 * and a start packet on the second, and clears stale state when the book (re)binds or recipes
 * reload.
 *
 * <p>1.21.1 works in {@code RecipeHolder} and the {@code (DDI)Z} mouseClicked; 26.x works in the
 * opaque {@code RecipeDisplayId} and the {@code MouseButtonEvent} mouseClicked, and ghosts via
 * {@code fillGhostRecipe(RecipeDisplay)} rather than {@code setupGhostRecipe}.
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {

    @Shadow
    @Final
    private RecipeBookPage recipeBookPage;

    //? if >=1.21.11 {
    /*@Shadow
    @Final
    private GhostSlots ghostSlots;

    // Clear a stale recipe-book preview ghost the next time the book draws it after a craft started
    // (flag set by CraftingScreenWatcher). Runs on the crafting screen, so a viewer detour at start
    // doesn't drop it; clears before drawing, so the ghost never shows over the deferred craft's items.
    @Inject(method = "extractGhostRecipe", at = @At("HEAD"))
    private void sprawlcrafting$clearStaleGhost(CallbackInfo ci) {
        if (RecipeBookGhost.consumeClear()) {
            this.ghostSlots.clear();
        }
    }

    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
            cancellable = true,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;tryPlaceRecipe(Lnet/minecraft/client/gui/screens/recipebook/RecipeCollection;Lnet/minecraft/world/item/crafting/display/RecipeDisplayId;Z)Z"))
    private void sprawlcrafting$interceptDeferredClick(MouseButtonEvent event, boolean doubleClick,
                                                       CallbackInfoReturnable<Boolean> cir) {
        RecipeCollection collection = recipeBookPage.getLastClickedRecipeCollection();
        // Second click on the button that owns the pending preview confirms THAT recipe.
        if (collection != null) {
            java.util.List<RecipeDisplayId> ids =
                    collection.getRecipes().stream().map(RecipeDisplayEntry::id).toList();
            if (DeferredClickState.pendingFor(ids) != null) {
                DeferredClickState.confirmPending();
                // Clear the preview ghost set on the first click. The deferred craft is now
                // queued; the vanilla place we cancel below (which would have cleared it) never
                // runs, and 26.x GhostSlots has no craftability-driven auto-clear (unlike
                // 1.21.1's GhostRecipe). Left set, the red ghost overlay lingers over the grid
                // for the whole craft — and over the real items once the final step fills it.
                this.ghostSlots.clear();
                cir.setReturnValue(true);
                return;
            }
        }
        RecipeDisplayId clicked = recipeBookPage.getLastClickedRecipe();
        if (clicked == null || !DeferredCraftableCache.isDeferredOnly(clicked)) {
            // Clicking away from a pending deferred preview to any other (e.g. red/uncraftable)
            // recipe: clear OUR preview ghost so it doesn't linger. Vanilla's tryPlaceRecipe below
            // clears the ghost only on a real place/re-preview — re-clicking an uncraftable recipe
            // early-returns first (RecipeBookComponent.tryPlaceRecipe), and our cancelled deferred
            // place left lastPlacedRecipe out of step — so the ghost we set can survive otherwise.
            // Gated to a pending preview so vanilla ghosts for ordinary clicks are untouched.
            if (DeferredClickState.hasPendingPreview()) {
                this.ghostSlots.clear();
            }
            DeferredClickState.clear(); // vanilla click: drop any pending preview
            return;
        }
        DeferredClickState.openPreview(clicked);
        // Show the clicked recipe as a grid ghost (vanilla cleared the previous one just before
        // this injection point); we clear it ourselves on the confirm click above.
        if (collection != null) {
            collection.getRecipes().stream().filter(entry -> entry.id().equals(clicked)).findFirst()
                    .ifPresent(entry -> ((RecipeBookComponent) (Object) this).fillGhostRecipe(entry.display()));
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "init(IILnet/minecraft/client/Minecraft;Z)V", at = @At("TAIL"))
    private void sprawlcrafting$clearPreviewOnInit(CallbackInfo ci) {
        DeferredClickState.clear();
    }

    // Right-click a red (unmakeable) recipe to open its gather list. HEAD so it runs before vanilla's
    // own handling; the hovered button (a RecipeButton) decides if it's actually red and opens.
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
            cancellable = true, at = @At("HEAD"))
    private void sprawlcrafting$interceptGatherClick(MouseButtonEvent event, boolean doubleClick,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 1 || !((RecipeBookComponent) (Object) this).isVisible()) {
            return;
        }
        if (!SprawlConfig.get().needsSystem()) {
            return; // gather/"needs" helper disabled: right-click stays vanilla
        }
        if (((RecipeBookPageAccessor) recipeBookPage).sprawlcrafting$overlay().isVisible()) {
            return; // variant overlay open: its right-clicks belong to OverlayRecipeComponentMixin,
                    // not the recipe button it happens to sit over (which would otherwise leak through)
        }
        RecipeButton hovered = ((RecipeBookPageAccessor) recipeBookPage).sprawlcrafting$hoveredButton();
        if (hovered instanceof GatherCandidate gather && gather.sprawlcrafting$tryOpenGatherList()) {
            cir.setReturnValue(true);
        }
    }*/
    //?} else {
    @Shadow
    protected RecipeBookMenu<?, ?> menu;

    @Shadow
    @Final
    protected GhostRecipe ghostRecipe;

    // Clear a stale recipe-book preview ghost the next time the book draws it after a craft started
    // (flag set by CraftingScreenWatcher). Runs on the crafting screen, so a viewer detour at start
    // doesn't drop it; clears before drawing, so the ghost never shows over the deferred craft's items.
    @Inject(method = "renderGhostRecipe", at = @At("HEAD"))
    private void sprawlcrafting$clearStaleGhost(CallbackInfo ci) {
        if (RecipeBookGhost.consumeClear()) {
            this.ghostRecipe.clear();
        }
    }

    @Inject(method = "mouseClicked(DDI)Z",
            cancellable = true,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePlaceRecipe(ILnet/minecraft/world/item/crafting/RecipeHolder;Z)V"))
    private void sprawlcrafting$interceptDeferredClick(double mouseX, double mouseY, int button,
                                                       CallbackInfoReturnable<Boolean> cir) {
        RecipeCollection collection = recipeBookPage.getLastClickedRecipeCollection();
        // Second click on the button that owns the pending preview confirms THAT recipe,
        // regardless of which variant a grouped button's animation is currently showing.
        if (collection != null && DeferredClickState.pendingFor(collection.getRecipes(false)) != null) {
            DeferredClickState.confirmPending();
            cir.setReturnValue(true);
            return;
        }
        RecipeHolder<?> clicked = recipeBookPage.getLastClickedRecipe();
        if (clicked == null || !DeferredCraftableCache.isDeferredOnly(clicked)) {
            DeferredClickState.clear(); // vanilla click: drop any pending preview
            return; // truly craftable (or unknown): vanilla places it into the grid
        }
        GridContext grid = DeferredCraftableCache.gridFor(menu.getGridWidth(), menu.getGridHeight());
        DeferredClickState.openPreview(clicked, grid);
        // Show the final recipe's layout as a grid ghost (vanilla cleared the previous
        // ghost just before this injection point), so the player sees what the last
        // intermediates feed into. Cleared again automatically on confirm.
        ((RecipeBookComponent) (Object) this).setupGhostRecipe(clicked, menu.slots);
        cir.setReturnValue(true);
    }

    @Inject(method = "init(IILnet/minecraft/client/Minecraft;ZLnet/minecraft/world/inventory/RecipeBookMenu;)V",
            at = @At("TAIL"))
    private void sprawlcrafting$clearPreviewOnInit(CallbackInfo ci) {
        DeferredClickState.clear();
    }

    // Right-click a red (unmakeable) recipe to open its gather list. HEAD so it runs before vanilla's
    // own handling; the hovered button (a RecipeButton) decides if it's actually red and opens.
    @Inject(method = "mouseClicked(DDI)Z", cancellable = true, at = @At("HEAD"))
    private void sprawlcrafting$interceptGatherClick(double mouseX, double mouseY, int button,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (button != 1 || !((RecipeBookComponent) (Object) this).isVisible()) {
            return;
        }
        if (!SprawlConfig.get().needsSystem()) {
            return; // gather/"needs" helper disabled: right-click stays vanilla
        }
        if (((RecipeBookPageAccessor) recipeBookPage).sprawlcrafting$overlay().isVisible()) {
            return; // variant overlay open: its right-clicks belong to OverlayRecipeComponentMixin,
                    // not the recipe button it happens to sit over (which would otherwise leak through)
        }
        RecipeButton hovered = ((RecipeBookPageAccessor) recipeBookPage).sprawlcrafting$hoveredButton();
        if (hovered instanceof GatherCandidate gather && gather.sprawlcrafting$tryOpenGatherList()) {
            cir.setReturnValue(true);
        }
    }
    //?}

    @Inject(method = "recipesUpdated()V", at = @At("HEAD"))
    private void sprawlcrafting$invalidateOnRecipesUpdated(CallbackInfo ci) {
        //? if >=1.21.11 {
        /*// 26.x: do NOT invalidate here. recipesUpdated fires on every recipe-book
        // Add/Remove/Settings packet (routine on production servers: recipe unlocks) and from
        // DeferredCraftableCache.accept's repaint poke. The synced sets are server truth with
        // no client-side regeneration path — wiping them would kill the yellow outlines until
        // the server's next inventory-keyed re-push. Vanilla's selectRecipes pass that follows
        // this HEAD re-marks collections from the sets via RecipeCollectionMixin.
        // The pending preview is dropped below; clear the grid ghost we painted for it too,
        // since 26.x GhostSlots has no craftability-driven auto-clear (1.21.1's GhostRecipe does).
        if (DeferredClickState.hasPendingPreview()) {
            this.ghostSlots.clear();
        }*/
        //?} else {
        // HEAD, not TAIL: drop the stale session/marks BEFORE vanilla's update re-runs the
        // craftable pass, so that pass rebuilds and re-marks atomically. (1.21.1 only: the
        // client re-solves locally; a datapack reload mutates the client RecipeManager in
        // place, which the session's identity check cannot see.)
        DeferredCraftableCache.invalidate();
        //?}
        // Deliberately ungated (on 26.x this also fires from accept's repaint poke): whenever
        // craftability data changed, a pending first-click preview is suspect — dropping it
        // costs one re-click, while confirming against changed data could start a wrong craft.
        DeferredClickState.clear();
    }
}
