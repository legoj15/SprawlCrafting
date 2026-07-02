package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.item.crafting.IRecipe;

/**
 * Makes the recipe book's craftable-only FILTER keep deferred-craftable recipes visible: the
 * funnel toggle filters pages on {@code RecipeList.containsCraftableRecipes()}
 * ({@code GuiRecipeBook.updateCollections}'s predicate), so without this every yellow recipe
 * silently vanishes the moment the player enables the filter — the highlight mixin only recolors
 * buttons that survive it. Injecting at the source also feeds every other consumer of the same
 * query (the button frame redirect in {@link GuiButtonRecipeMixin} becomes a harmless
 * belt-and-suspenders). Classification is solver-cached per recipe ({@link ClientPlanCache}), the
 * same per-frame cost profile the button mixin already has.
 */
@Mixin(RecipeList.class)
public class RecipeListMixin {

    @Inject(method = "containsCraftableRecipes", at = @At("RETURN"), cancellable = true, require = 0)
    private void sprawlcrafting$deferredCountsAsCraftable(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null) {
                return;
            }
            GridContext grid = GridContext.current(mc.player);
            CraftPlanner.Session session = ClientPlanCache.get(mc.player, grid);
            for (IRecipe recipe : ((RecipeList) (Object) this).getRecipes()) {
                if (recipe.getRegistryName() != null
                        && session.classify(recipe) == CraftPlanner.Craftability.DEFERRED) {
                    cir.setReturnValue(Boolean.TRUE);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Never let solver classification break the recipe book; vanilla result stands.
        }
    }
}
