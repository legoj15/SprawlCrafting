package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;

/**
 * Exposes the recipe book page's hovered button so {@code RecipeBookComponentMixin} can target it on a
 * right-click, plus the variant overlay so that mixin can stand down while the overlay is open (its
 * right-clicks belong to {@code OverlayRecipeComponentMixin}). Both field types are the same on both
 * versions ({@code RecipeButton} / {@code OverlayRecipeComponent}).
 */
@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {

    @Accessor("hoveredButton")
    RecipeButton sprawlcrafting$hoveredButton();

    @Accessor("overlay")
    OverlayRecipeComponent sprawlcrafting$overlay();
}
