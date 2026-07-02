package com.legoj15.sprawlcrafting.forge.mixin.jei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import mezz.jei.api.recipe.IRecipeWrapper;

/**
 * Read access to {@code mezz.jei.gui.recipes.RecipeLayout}'s private {@code recipeWrapper} — the
 * one place JEI still knows WHICH recipe a layout displays. 4.x's public API never exposes it, so
 * without this the transfer handler must brute-force match the displayed stacks against the whole
 * recipe registry on every validation pass (and can mismatch recipes with identical displays).
 * Field name and type ({@code IRecipeWrapper}, javap-verified against 4.16.1.1013) survive in the
 * dist jar and HEI (mod classes aren't obfuscated); if a fork ever renames it the mixin silently
 * skips and the handler falls back to the brute-force scan ({@code instanceof} check on the
 * layout).
 */
@Mixin(targets = "mezz.jei.gui.recipes.RecipeLayout", remap = false)
public interface RecipeLayoutAccessor {

    @Accessor(value = "recipeWrapper", remap = false)
    IRecipeWrapper sprawlcrafting$getRecipeWrapper();
}
