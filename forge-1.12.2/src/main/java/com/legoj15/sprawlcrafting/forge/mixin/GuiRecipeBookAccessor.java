package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.recipebook.GhostRecipe;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;

/**
 * Read access to the recipe book's private final {@code ghostRecipe} (the translucent overlay the
 * book draws over the crafting grid). An {@code @Accessor}, not a {@code @Shadow}: accessor targets
 * go through the refmap (deobf name to SRG {@code field_*}), while a shadow field keeps its MCP
 * name in the reobf jar (see {@link RecipeListMixin}). Read-only; the ghost is mutated only through
 * its own public methods ({@code clear()}, {@code setRecipe()}, ...).
 *
 * <p>An accessor has no {@code require = 0}; with the config's {@code "required": true} a lookup
 * failure fails at mixin application. Accepted: 1.12.2's stable_39 mappings are frozen and the
 * name is checked against joined.srg ({@code GuiRecipeBook/ghostRecipe} = {@code field_191915_z}).
 */
@Mixin(GuiRecipeBook.class)
public interface GuiRecipeBookAccessor {

    @Accessor("ghostRecipe")
    GhostRecipe sprawlcrafting$getGhostRecipe();
}
