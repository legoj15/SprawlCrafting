package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.legoj15.sprawlcrafting.client.GatherCandidate;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
//? if >=1.21.11 {
/*import net.minecraft.world.item.crafting.display.RecipeDisplayId;*/
//?} else {
import com.legoj15.sprawlcrafting.craft.GridContext;
import net.minecraft.world.item.crafting.RecipeHolder;
//?}

/**
 * Makes a variant button inside the recipe book's "more recipes" overlay a gather candidate, so a
 * right-click on a RED variant opens its gather list — the same affordance the single-recipe button
 * has. Targets the package-private inner class by name (one mixin covers the crafting + smelting
 * subclasses, since the fields live on their shared base).
 *
 * <p>The gate is the button's own {@code isCraftable} flag: vanilla's craftable/uncraftable split
 * already counts our deferred (yellow) recipes as craftable (see {@code RecipeCollectionMixin}), so
 * {@code isCraftable == false} means a genuinely red variant — exactly the red sprite the player sees.
 * {@code OverlayRecipeComponentMixin} routes the right-click here.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent$OverlayRecipeButton")
public abstract class OverlayRecipeButtonMixin implements GatherCandidate {

    @Shadow
    @Final
    private boolean isCraftable;

    //? if >=1.21.11 {
    /*@Shadow
    @Final
    private RecipeDisplayId recipe;

    @Override
    public boolean sprawlcrafting$tryOpenGatherList() {
        if (isCraftable || recipe == null) {
            return false; // craftable (incl. our deferred yellow): vanilla's left-click handles it
        }
        MissingIngredientsView.open(recipe);
        return true;
    }*/
    //?} else {
    @Shadow
    @Final
    RecipeHolder<?> recipe;

    @Override
    public boolean sprawlcrafting$tryOpenGatherList() {
        if (isCraftable || recipe == null) {
            return false; // craftable (incl. our deferred yellow): vanilla's left-click handles it
        }
        MissingIngredientsView.open(recipe, GridContext.CRAFTING_TABLE);
        return true;
    }
    //?}
}
