package com.legoj15.sprawlcrafting.craft;

import java.util.List;
import java.util.Optional;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.ICraftingRecipe;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.util.ResourceLocation;

/**
 * Recipe identity and lookup helpers. On 1.16.5 a recipe carries its own id ({@code Recipe.getId()});
 * there is no {@code RecipeHolder} wrapper, so the modern helpers collapse to thin pass-throughs.
 */
public final class RecipeIds {

    private RecipeIds() {
    }

    public static Optional<? extends IRecipe<?>> byId(RecipeManager recipes, ResourceLocation id) {
        return recipes.byKey(id);
    }

    /** Every crafting recipe the manager knows. */
    public static List<ICraftingRecipe> craftingRecipes(RecipeManager recipes) {
        return recipes.getAllRecipesFor(IRecipeType.CRAFTING);
    }

    /** The recipe's nominal result (never executes the recipe, so it is safe for every crafting recipe). */
    public static ItemStack resultOf(IRecipe<?> recipe) {
        ItemStack result = recipe.getResultItem();
        return result == null ? ItemStack.EMPTY : result;
    }
}
