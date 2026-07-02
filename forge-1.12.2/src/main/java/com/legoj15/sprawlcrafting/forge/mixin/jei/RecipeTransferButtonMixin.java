package com.legoj15.sprawlcrafting.forge.mixin.jei;

import com.legoj15.sprawlcrafting.forge.compat.jei.MissingResourcesError;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiButton;

/**
 * Re-enables the JEI "+" button when the validation error is SprawlCrafting's own
 * {@code MissingResourcesError}: JEI's {@code init} greys every USER_FACING error out, but ours is
 * actionable — the click opens the missing-resources gather screen (modern's orange button), which
 * the transfer handler serves when asked to transfer an unsolvable recipe. {@code init} is JEI's
 * own method (source-named in every dist jar and HEI — no SRG dual-naming needed), and stock
 * errors are untouched, so a genuinely internal error still hides/greys exactly as before.
 */
@Mixin(targets = "mezz.jei.gui.recipes.RecipeTransferButton", remap = false)
public class RecipeTransferButtonMixin {

    @Inject(method = "init(Lnet/minecraft/inventory/Container;Lnet/minecraft/entity/player/EntityPlayer;)V",
            at = @At("TAIL"), require = 0)
    private void sprawlcrafting$enableGatherClick(CallbackInfo ci) {
        if (((RecipeTransferButtonAccessor) this).sprawlcrafting$getRecipeTransferError()
                instanceof MissingResourcesError) {
            ((GuiButton) (Object) this).enabled = true;
        }
    }
}
