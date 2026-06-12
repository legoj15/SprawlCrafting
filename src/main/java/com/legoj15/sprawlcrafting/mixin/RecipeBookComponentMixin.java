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

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
//? if >=1.21.11 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;*/
//?} else {
import com.legoj15.sprawlcrafting.craft.GridContext;
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
    /*@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
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
                cir.setReturnValue(true);
                return;
            }
        }
        RecipeDisplayId clicked = recipeBookPage.getLastClickedRecipe();
        if (clicked == null || !DeferredCraftableCache.isDeferredOnly(clicked)) {
            DeferredClickState.clear(); // vanilla click: drop any pending preview
            return;
        }
        DeferredClickState.openPreview(clicked);
        // Show the clicked recipe as a grid ghost (vanilla cleared the previous one just before
        // this injection point); cleared again automatically on confirm.
        if (collection != null) {
            collection.getRecipes().stream().filter(entry -> entry.id().equals(clicked)).findFirst()
                    .ifPresent(entry -> ((RecipeBookComponent) (Object) this).fillGhostRecipe(entry.display()));
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "init(IILnet/minecraft/client/Minecraft;Z)V", at = @At("TAIL"))
    private void sprawlcrafting$clearPreviewOnInit(CallbackInfo ci) {
        DeferredClickState.clear();
    }*/
    //?} else {
    @Shadow
    protected RecipeBookMenu<?, ?> menu;

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
    //?}

    @Inject(method = "recipesUpdated()V", at = @At("HEAD"))
    private void sprawlcrafting$invalidateOnRecipesUpdated(CallbackInfo ci) {
        // HEAD, not TAIL: drop the stale session/marks BEFORE vanilla's update re-runs the
        // craftable pass, so that pass rebuilds and re-marks atomically.
        DeferredCraftableCache.invalidate();
        DeferredClickState.clear();
    }
}
