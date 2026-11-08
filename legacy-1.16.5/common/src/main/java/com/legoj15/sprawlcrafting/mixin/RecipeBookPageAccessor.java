package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.recipebook.RecipeBookPage;
import net.minecraft.client.gui.recipebook.RecipeOverlayGui;
import net.minecraft.client.gui.recipebook.RecipeWidget;

/**
 * Exposes the recipe book page's hovered button so {@code RecipeBookGuiMixin} can target it on a
 * right-click, plus the variant overlay so that mixin can stand down while the overlay is open.
 */
@Mixin(RecipeBookPage.class)
public interface RecipeBookPageAccessor {

    @Accessor("hoveredButton")
    RecipeWidget sprawlcrafting$hoveredButton();

    @Accessor("overlay")
    RecipeOverlayGui sprawlcrafting$overlay();
}
