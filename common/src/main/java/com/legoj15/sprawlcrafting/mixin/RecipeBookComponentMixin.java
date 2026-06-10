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
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The preview-then-confirm flow for yellow (deferred-craftable) recipes:
 * <ul>
 *   <li>Intercepts clicks right before vanilla would send the place-recipe packet —
 *       the single choke point both the grid buttons and the variant overlay route
 *       through. Yellow recipes get a client-computed plan preview on first click
 *       (shown in the button's own tooltip, see RecipeButtonMixin) and a start packet
 *       on the second; white recipes keep vanilla behavior.</li>
 *   <li>Clears stale preview/cache state when the book (re)binds or recipes reload.</li>
 * </ul>
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {

    @Shadow
    protected RecipeBookMenu<?, ?> menu;

    @Shadow
    @Final
    private RecipeBookPage recipeBookPage;

    @Inject(method = "mouseClicked(DDI)Z",
            cancellable = true,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePlaceRecipe(ILnet/minecraft/world/item/crafting/RecipeHolder;Z)V"))
    private void sprawlcrafting$interceptDeferredClick(double mouseX, double mouseY, int button,
                                                       CallbackInfoReturnable<Boolean> cir) {
        RecipeHolder<?> clicked = recipeBookPage.getLastClickedRecipe();
        if (clicked == null || !DeferredCraftableCache.isDeferredOnly(clicked)) {
            DeferredClickState.clear(); // vanilla click: drop any pending preview
            return; // truly craftable (or unknown): vanilla places it into the grid
        }
        GridContext grid = DeferredCraftableCache.gridFor(menu.getGridWidth(), menu.getGridHeight());
        DeferredClickState.click(clicked, grid);
        cir.setReturnValue(true);
    }

    @Inject(method = "init(IILnet/minecraft/client/Minecraft;ZLnet/minecraft/world/inventory/RecipeBookMenu;)V",
            at = @At("TAIL"))
    private void sprawlcrafting$clearPreviewOnInit(CallbackInfo ci) {
        DeferredClickState.clear();
    }

    @Inject(method = "recipesUpdated()V", at = @At("TAIL"))
    private void sprawlcrafting$invalidateOnRecipesUpdated(CallbackInfo ci) {
        DeferredCraftableCache.invalidate();
        DeferredClickState.clear();
    }
}
