package com.legoj15.sprawlcrafting.forge.mixin;

import java.util.Iterator;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.forge.client.RecipeBookHighlight;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;

import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.item.crafting.IRecipe;

/**
 * Puts deferred-craftable recipes in the recipe list's CRAFTABLE bucket, so every vanilla consumer
 * treats them the way the yellow highlight presents them:
 * <ul>
 *   <li>{@code containsCraftableRecipes()}: the craftable-only FILTER
 *       ({@code GuiRecipeBook.updateCollections}'s predicate) keeps a list whose only makeable
 *       recipes are deferred, instead of every yellow recipe vanishing when the filter is on.</li>
 *   <li>{@code getDisplayRecipes(boolean)}: the ordered views behind the page button
 *       ({@code GuiButtonRecipe.getOrderedRecipes}) and the alternates bubble
 *       ({@code GuiRecipeOverlay.init}). Deferred recipes move from the not-craftable view to the
 *       craftable one, so the bubble draws them with the neutral grey frame (its per-button
 *       craftable flag comes from which view a recipe was in, so no final field is touched) and
 *       still lists them under the filter. Without this the page button of a deferred-only list
 *       kept by the filter had an EMPTY ordered list and divided by zero in {@code drawButton}.</li>
 *   <li>{@code getRecipes(boolean)} with {@code true}: the filtered count that decides the
 *       stacked-frame look and the "more recipes" tooltip, kept in step with the bubble.</li>
 * </ul>
 * A deferred recipe still has to be unlocked and fit the grid, exactly like a vanilla craftable one.
 *
 * <p>TRAP: no {@code @Shadow} fields here (or in any 1.12.2 mixin). RFG's reobf renames only
 * members owned by Minecraft classes, and this build does not feed the Mixin AP's own SRG output
 * back into reobf, so a shadowed field keeps its MCP name in the production jar and fails to
 * resolve against the SRG-named target (a hard error, {@code require} does not cover it). Calls
 * through the target's public methods are renamed normally, so the bucket is read through those.
 * Classification is solver-cached per recipe ({@link RecipeBookHighlight}), and every injector
 * degrades to the vanilla result on failure.
 */
@Mixin(RecipeList.class)
public class RecipeListMixin {

    @Inject(method = "containsCraftableRecipes", at = @At("RETURN"), cancellable = true, require = 0)
    private void sprawlcrafting$deferredCountsAsCraftable(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        try {
            CraftPlanner.Session session = RecipeBookHighlight.session();
            for (IRecipe recipe : ((RecipeList) (Object) this).getRecipes()) {
                if (RecipeBookHighlight.isDeferred(session, recipe)) {
                    cir.setReturnValue(Boolean.TRUE);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Never let solver classification break the recipe book; vanilla result stands.
        }
    }

    @Inject(method = "getDisplayRecipes", at = @At("RETURN"), require = 0)
    private void sprawlcrafting$deferredDisplaysAsCraftable(boolean onlyCraftable,
                                                           CallbackInfoReturnable<List<IRecipe>> cir) {
        try {
            List<IRecipe> list = cir.getReturnValue();
            if (onlyCraftable) {
                appendDeferred(list);
            } else {
                // Vanilla builds a fresh ArrayList for every call, so editing it in place is safe.
                CraftPlanner.Session session = RecipeBookHighlight.session();
                for (Iterator<IRecipe> it = list.iterator(); it.hasNext(); ) {
                    if (RecipeBookHighlight.isDeferred(session, it.next())) {
                        it.remove();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Vanilla view stands.
        }
    }

    @Inject(method = "getRecipes(Z)Ljava/util/List;", at = @At("RETURN"), require = 0)
    private void sprawlcrafting$deferredCountsInFilter(boolean onlyCraftable,
                                                      CallbackInfoReturnable<List<IRecipe>> cir) {
        if (!onlyCraftable) {
            return;
        }
        try {
            appendDeferred(cir.getReturnValue());
        } catch (Throwable ignored) {
            // Vanilla view stands.
        }
    }

    /**
     * Appends the unlocked, grid-fitting, not-directly-craftable recipes that are deferred-craftable,
     * in list order. {@code getRecipes(false)} is vanilla's unlocked-and-fits view (our
     * {@code getRecipes(Z)} injector returns early for {@code false}, so this does not recurse).
     */
    private void appendDeferred(List<IRecipe> list) {
        CraftPlanner.Session session = RecipeBookHighlight.session();
        if (session == null) {
            return;
        }
        RecipeList self = (RecipeList) (Object) this;
        for (IRecipe recipe : self.getRecipes(false)) {
            if (!self.isCraftable(recipe) && RecipeBookHighlight.isDeferred(session, recipe)) {
                list.add(recipe);
            }
        }
    }
}
