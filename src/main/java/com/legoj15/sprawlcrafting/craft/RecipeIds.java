package com.legoj15.sprawlcrafting.craft;

import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Cross-version recipe-identity and lookup shims. The mod keeps {@link ResourceLocation}
 * as its canonical recipe-id type everywhere (network payloads, {@link CraftStep},
 * {@link CraftJob}, and the client caches). MC 1.21.11+ (the 26.x line) reworked recipes
 * to key on {@code ResourceKey<Recipe<?>>} and removed {@code getAllRecipesFor}; these
 * helpers confine that divergence to one place. On 1.21.1 they are thin pass-throughs.
 */
public final class RecipeIds {

    private RecipeIds() {
    }

    /** The recipe's {@link ResourceLocation}. 26.x {@code RecipeHolder.id()} is a ResourceKey; unwrap it. */
    public static ResourceLocation id(RecipeHolder<?> holder) {
        //? if >=1.21.11 {
        /*return holder.id().identifier();*/
        //?} else {
        return holder.id();
        //?}
    }

    /** Look up a recipe by its {@link ResourceLocation} through the version-specific {@code byKey}. */
    public static Optional<RecipeHolder<?>> byId(RecipeManager recipes, ResourceLocation id) {
        //? if >=1.21.11 {
        /*return recipes.byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, id));*/
        //?} else {
        return recipes.byKey(id);
        //?}
    }

    /** Every crafting recipe the manager knows — server-side enumeration only. */
    public static Iterable<RecipeHolder<CraftingRecipe>> craftingRecipes(RecipeManager recipes) {
        //? if >=1.21.11 {
        /*// 26.x removed getAllRecipesFor and recipeMap().byType is NeoForge-only; iterate the
        // vanilla holder set and keep the crafting recipes (cross-loader safe).
        java.util.List<RecipeHolder<CraftingRecipe>> crafting = new java.util.ArrayList<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            if (holder.value() instanceof CraftingRecipe) {
                @SuppressWarnings("unchecked")
                RecipeHolder<CraftingRecipe> typed = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder;
                crafting.add(typed);
            }
        }
        return crafting;*/
        //?} else {
        return recipes.getAllRecipesFor(RecipeType.CRAFTING);
        //?}
    }

    /**
     * The recipe's nominal display result — what 1.21.1's {@code getResultItem} returned. 26.x
     * removed it; resolve the recipe's display result instead of calling {@code assemble}. Calling
     * {@code assemble(CraftingInput.EMPTY)} CRASHES on non-special recipes that read the input
     * (e.g. {@code ImbueRecipe}, which fires the moment a player is in-world); the display result
     * never executes the recipe, so it is safe for every crafting recipe.
     */
    public static ItemStack resultOf(Recipe<?> recipe, HolderLookup.Provider registries) {
        //? if >=1.21.11 {
        /*java.util.List<net.minecraft.world.item.crafting.display.RecipeDisplay> displays = recipe.display();
        if (displays.isEmpty()) {
            return ItemStack.EMPTY;
        }
        net.minecraft.util.context.ContextMap ctx = new net.minecraft.util.context.ContextMap.Builder()
                .withParameter(net.minecraft.world.item.crafting.display.SlotDisplayContext.REGISTRIES, registries)
                .create(net.minecraft.world.item.crafting.display.SlotDisplayContext.CONTEXT);
        return displays.get(0).result().resolveForFirstStack(ctx);*/
        //?} else {
        return recipe.getResultItem(registries);
        //?}
    }
}
