package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.item.crafting.IRecipe;

/**
 * Read access to the recipe an alternates-bubble button shows ({@code GuiRecipeOverlay$Button}'s
 * private final {@code recipe}). An {@code @Accessor}, not a {@code @Shadow}: accessor targets go
 * through the refmap (deobf name to SRG {@code field_*}), while a shadow field would keep its MCP
 * name in the reobf jar and fail in production (see {@link RecipeListMixin}). Read-only.
 */
@Mixin(targets = "net.minecraft.client.gui.recipebook.GuiRecipeOverlay$Button")
public interface GuiRecipeOverlayButtonAccessor {

    @Accessor("recipe")
    IRecipe sprawlcrafting$getRecipe();
}
