package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeBook;
import net.minecraft.item.crafting.RecipeItemHelper;

/**
 * Recipe collection (Mojmap {@code RecipeCollection}, Forge-1.16.5 {@code RecipeList}): after vanilla
 * decides which recipes are craftable from the grid + inventory, additionally mark recipes solvable from
 * raw resources as craftable. This one hook makes the craftable-only filter, button frames, overlay
 * popup and {@code isCraftable} all agree; {@link DeferredCraftableCache} remembers which entries were
 * deferred-only so the button can tint them yellow instead of white.
 */
@Mixin(RecipeList.class)
public abstract class RecipeListMixin {

    @Inject(method = "canCraft", at = @At("TAIL"))
    private void sprawlcrafting$markDeferredCraftable(RecipeItemHelper handler, int width, int height,
                                                      RecipeBook book, CallbackInfo ci) {
        if (width < 2 || height < 2) {
            return; // furnace-style books: deferred crafting doesn't apply
        }
        RecipeList self = (RecipeList) (Object) this;
        GridContext grid = DeferredCraftableCache.gridFor(width, height);
        for (IRecipe<?> recipe : self.getRecipes(false)) {
            if (!self.isCraftable(recipe) && DeferredCraftableCache.isSolvable(recipe, grid)) {
                ((RecipeListAccessor) self).sprawlcrafting$craftable().add(recipe);
                DeferredCraftableCache.markDeferredOnly(recipe);
            }
        }
    }
}
