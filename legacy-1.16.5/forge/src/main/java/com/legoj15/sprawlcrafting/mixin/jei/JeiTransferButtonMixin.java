package com.legoj15.sprawlcrafting.mixin.jei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.legoj15.sprawlcrafting.compat.jei.DeferredCraftingTransferHandler;

import mezz.jei.api.recipe.transfer.IRecipeTransferError;

/**
 * JEI 7's "+" button fills a COSMETIC error with a fixed amber ({@code 0x80FFA500}); there is no API for
 * a per-error colour. This swaps that constant for our yellow (deferred craft) / orange (gather list).
 * Any other mod's COSMETIC error keeps JEI's amber.
 *
 * <p>{@code remap = false}: JEI is a mod, so its class and field names are never obfuscated, but the
 * render method is MC's {@code Widget#render}, which is {@code render} in dev and
 * {@code func_230430_a_} (SRG) in production. Both names are listed; exactly one exists per environment.
 * Applied only when JEI is installed ({@code JeiMixinPlugin} adds it).
 */
@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipeTransferButton", remap = false)
public abstract class JeiTransferButtonMixin {

    // javap of JEI 7.8.1's RecipeTransferButton#render: "ldc int -2130729728" = 0x80FFA500.
    private static final int JEI_COSMETIC_FILL = 0x80FFA500;

    @Shadow
    private IRecipeTransferError recipeTransferError;

    @ModifyConstant(method = {"render", "func_230430_a_"},
            constant = @Constant(intValue = JEI_COSMETIC_FILL), require = 1)
    private int sprawlcrafting$tintButton(int original) {
        return DeferredCraftingTransferHandler.highlightColor(recipeTransferError, original);
    }
}
