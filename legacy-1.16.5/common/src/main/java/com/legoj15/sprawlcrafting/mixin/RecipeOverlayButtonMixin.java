package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.legoj15.sprawlcrafting.client.GatherCandidate;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.item.crafting.IRecipe;

/**
 * Makes a variant button inside the recipe book's "more recipes" overlay a gather candidate, so a
 * right-click on a RED variant opens its gather list. Targets the package-private inner class by name
 * (one mixin covers the crafting + furnace subclasses, since the fields live on the shared base).
 *
 * <p>The gate is the button's own {@code isCraftable} flag: vanilla's craftable split already counts our
 * deferred (yellow) recipes as craftable (see {@code RecipeListMixin}), so {@code isCraftable == false}
 * means a genuinely red variant.
 */
@Mixin(targets = "net.minecraft.client.gui.recipebook.RecipeOverlayGui$RecipeButtonWidget")
public abstract class RecipeOverlayButtonMixin implements GatherCandidate {

    @Shadow
    @Final
    private boolean isCraftable;

    @Shadow
    @Final
    private IRecipe<?> recipe;

    @Override
    public boolean sprawlcrafting$tryOpenGatherList() {
        if (isCraftable || recipe == null) {
            return false;
        }
        MissingIngredientsView.open(recipe, GridContext.CRAFTING_TABLE);
        return true;
    }
}
