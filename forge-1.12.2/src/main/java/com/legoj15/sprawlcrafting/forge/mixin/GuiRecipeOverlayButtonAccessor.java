package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.item.crafting.IRecipe;

/**
 * Read access to an alternates-bubble button's private final fields ({@code GuiRecipeOverlay$Button}'s
 * {@code recipe} and {@code isCraftable}, the latter being the frame colour vanilla fixed when the
 * bubble was built). An {@code @Accessor}, not a {@code @Shadow}: accessor targets go through the
 * refmap (deobf name to SRG {@code field_*}), while a shadow field keeps its MCP name in the reobf
 * jar (see {@link RecipeListMixin}). Read-only.
 *
 * <p>An accessor has no {@code require = 0}; with the config's {@code "required": true} a lookup
 * failure fails at mixin application. Accepted: 1.12.2's stable_39 mappings are frozen and both
 * names are checked against {@code fields.csv} ({@code field_193924_p} = recipe,
 * {@code field_193925_q} = isCraftable).
 */
@Mixin(targets = "net.minecraft.client.gui.recipebook.GuiRecipeOverlay$Button")
public interface GuiRecipeOverlayButtonAccessor {

    @Accessor("recipe")
    IRecipe sprawlcrafting$getRecipe();

    @Accessor("isCraftable")
    boolean sprawlcrafting$isCraftable();
}
