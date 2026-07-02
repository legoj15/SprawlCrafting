package com.legoj15.sprawlcrafting.forge.mixin.jei;

import com.legoj15.sprawlcrafting.forge.compat.jei.JeiTintState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import mezz.jei.gui.recipes.RecipeTransferButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Tints the JEI "+" transfer button's icon yellow when the recipe it would start is a DEFERRED
 * sprawl-craft — the 1.12.2 stand-in for modern JEI's {@code getButtonHighlightColor}, which the
 * 4.x API has no equivalent of: the icon tint is a hardcoded constant inside
 * {@code GuiIconButtonSmall}'s draw ({@code RecipeTransferButton} inherits it), applied by the
 * SECOND {@code GlStateManager.color} call immediately before the icon renders. We redirect
 * exactly that call, gated to transfer buttons whose layout {@link JeiTintState} classified as
 * deferred during validation.
 *
 * <p><b>Why two identical redirects:</b> {@code drawButton} overrides a vanilla method, so in
 * PRODUCTION jars (JEI and HEI alike, bytecode-verified) the method is the SRG name
 * {@code func_191745_a} calling {@code GlStateManager.func_179131_c} — while the RFG dev
 * workspace uses the MCP names. A single MCP-named redirect would bind in dev and silently no-op
 * in every real install (the trap the placement review caught). So both spellings are declared
 * with {@code require = 0}; exactly one binds per environment. The tint multiplies the incoming
 * grey level, so JEI's own hover brightening still reads through.
 */
@Mixin(targets = "mezz.jei.gui.elements.GuiIconButtonSmall", remap = false)
public class GuiIconButtonSmallMixin {

    // PRODUCTION: SRG names (JEI dist + HEI, verified: 2 color calls, ordinal 1 = the icon tint).
    @Redirect(method = "func_191745_a(Lnet/minecraft/client/Minecraft;IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;func_179131_c(FFFF)V",
                    ordinal = 1),
            require = 0)
    private void sprawlcrafting$tintIconProd(float red, float green, float blue, float alpha) {
        sprawlcrafting$tintIcon(red, green, blue, alpha);
    }

    // DEV (RFG deobf workspace): the same injection under MCP names.
    @Redirect(method = "drawButton(Lnet/minecraft/client/Minecraft;IIF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V",
                    ordinal = 1),
            require = 0)
    private void sprawlcrafting$tintIconDev(float red, float green, float blue, float alpha) {
        sprawlcrafting$tintIcon(red, green, blue, alpha);
    }

    private void sprawlcrafting$tintIcon(float red, float green, float blue, float alpha) {
        if ((Object) this instanceof RecipeTransferButton && ((GuiButton) (Object) this).enabled) {
            JeiTintState.Tint tint = JeiTintState.get(
                    ((RecipeTransferButtonAccessor) this).sprawlcrafting$getRecipeLayout());
            if (tint == JeiTintState.Tint.DEFERRED) {
                // Multiply toward the mod's deferred yellow (the recipe-book highlight family):
                // enabled grey becomes warm yellow, JEI's brighter hover grey a brighter yellow.
                GlStateManager.color(red, green * 0.91F, blue * 0.30F, alpha);
                return;
            }
            if (tint == JeiTintState.Tint.GATHER) {
                // Orange, matching modern's gather button: the click opens the missing-resources
                // screen (RecipeTransferButtonMixin re-enables the button for exactly this case).
                GlStateManager.color(red, green * 0.45F, 0.0F, alpha);
                return;
            }
        }
        GlStateManager.color(red, green, blue, alpha);
    }
}
