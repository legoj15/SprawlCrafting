package com.legoj15.sprawlcrafting.forge.mixin.jei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.gui.recipes.RecipeLayout;

/**
 * Read access to {@code mezz.jei.gui.recipes.RecipeTransferButton}'s private {@code recipeLayout},
 * set alongside the validation error. The tint mixin uses it as the key into
 * {@code JeiTintState} — the layout is the only stable identity linking the drawn button back to
 * the validation pass that classified its recipe. Field name and type ({@code RecipeLayout},
 * javap-verified against 4.16.1.1013) are stable in the dist jar and HEI.
 */
@Mixin(targets = "mezz.jei.gui.recipes.RecipeTransferButton", remap = false)
public interface RecipeTransferButtonAccessor {

    @Accessor(value = "recipeLayout", remap = false)
    RecipeLayout sprawlcrafting$getRecipeLayout();

    @Accessor(value = "recipeTransferError", remap = false)
    IRecipeTransferError sprawlcrafting$getRecipeTransferError();
}
