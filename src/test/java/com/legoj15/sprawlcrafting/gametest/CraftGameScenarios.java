package com.legoj15.sprawlcrafting.gametest;

import com.legoj15.sprawlcrafting.craft.RecipeIds;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Loader-agnostic in-game (server {@code GameTest}) scenarios for the version-gated MC-integration
 * layer — the code unit tests can't reach (it needs a live {@code RecipeManager}/registry) and the
 * boot/connect harness doesn't exercise (it never crafts). Each method takes a vanilla
 * {@link GameTestHelper}; per-loader registration wires them into NeoForge's / Fabric's runner.
 *
 * <p>These guard the exact cliffs the 26.x port fell off: {@code RecipeIds.resultOf} (the
 * {@code ImbueRecipe.assemble(EMPTY)} world-load crash) and {@code RecipeIds.craftingRecipes}
 * (the NeoForge-only {@code recipeMap()} vs vanilla {@code getRecipes()} split).
 */
public final class CraftGameScenarios {

    private CraftGameScenarios() {
    }

    /**
     * The regression guard for the world-load crash: resolving the display result of EVERY crafting
     * recipe the server knows must never throw. {@code RecipeIds.resultOf} replaced an
     * {@code assemble(CraftingInput.EMPTY)} call that crashed on input-reading recipes (ImbueRecipe)
     * the moment a world loaded; this asserts it at recipe granularity. Also pins that
     * {@code craftingRecipes()} enumerates via the vanilla path (it would not compile/run here if it
     * still used NeoForge-only {@code recipeMap()}), yields only {@link CraftingRecipe} holders, and
     * is non-empty (so a broken enumeration can't pass vacuously).
     */
    public static void allCraftingRecipesResolveResultWithoutThrowing(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getServer().getRecipeManager();
        int count = 0;
        for (RecipeHolder<CraftingRecipe> holder : RecipeIds.craftingRecipes(recipes)) {
            CraftingRecipe recipe = holder.value(); // craftingRecipes() must yield only CraftingRecipe
            // resolveForFirstStack / getResultItem must not throw for any recipe (the ImbueRecipe guard).
            ItemStack result = RecipeIds.resultOf(recipe, helper.getLevel().registryAccess());
            helper.assertTrue(result != null, "resultOf returned null for " + RecipeIds.id(holder));
            count++;
        }
        helper.assertTrue(count > 0, "expected the server to know at least one crafting recipe");
        helper.succeed();
    }

    /**
     * A known vanilla recipe resolves to its expected result item, proving {@code resultOf} returns
     * the real display result (not EMPTY / not a placeholder). Chest is shaped, non-special, and
     * present in every vanilla recipe set on both MC lines.
     */
    public static void knownRecipeResolvesExpectedResult(GameTestHelper helper) {
        RecipeManager recipes = helper.getLevel().getServer().getRecipeManager();
        boolean sawChest = false;
        for (RecipeHolder<CraftingRecipe> holder : RecipeIds.craftingRecipes(recipes)) {
            var id = RecipeIds.id(holder);
            if (!id.toString().equals("minecraft:chest")) {
                continue;
            }
            ItemStack result = RecipeIds.resultOf(holder.value(), helper.getLevel().registryAccess());
            helper.assertTrue(result.is(net.minecraft.world.item.Items.CHEST),
                    "minecraft:chest should resolve to a chest, got " + result);
            helper.assertTrue(!result.isEmpty(), "minecraft:chest result must not be empty");
            sawChest = true;
        }
        helper.assertTrue(sawChest, "minecraft:chest recipe not found in the crafting recipe set");
        helper.succeed();
    }
}
