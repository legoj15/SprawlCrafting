package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.legoj15.sprawlcrafting.forge.client.RecipeBookHighlight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * Yellow "deferred craftable" outline on the recipe book's alternates bubble (the
 * {@code GuiRecipeOverlay} opened by right-clicking a recipe button with several recipes), the
 * same stroke {@link GuiButtonRecipeMixin} puts on page buttons.
 *
 * <p>Render-only: it reads the button's recipe ({@link GuiRecipeOverlayButtonAccessor}) and draws
 * on top after vanilla finishes. The grey frame underneath comes from {@link RecipeListMixin},
 * which lists deferred recipes in the craftable view the bubble is built from, so the button's
 * final craftable flag is never written. The outline is only drawn on a grey-framed button (the
 * flag is read, never written), so a status change while the bubble is open cannot put yellow on
 * red. The target is a package-private inner class, named by
 * string; {@code drawButton} is a Minecraft member, so the annotation processor remaps it to SRG
 * in the refmap (unlike the JEI targets, which needed hand-written SRG names).
 * {@code require = 0} keeps a mapping mismatch at "no outline" rather than a crash.
 */
@Mixin(targets = "net.minecraft.client.gui.recipebook.GuiRecipeOverlay$Button")
public abstract class GuiRecipeOverlayButtonMixin {

    @Inject(method = "drawButton", at = @At("TAIL"), require = 0)
    private void sprawlcrafting$highlightDeferred(Minecraft mc, int mouseX, int mouseY,
                                                  float partialTicks, CallbackInfo ci) {
        try {
            Object self = this;
            GuiRecipeOverlayButtonAccessor fields = (GuiRecipeOverlayButtonAccessor) self;
            // The frame colour is a snapshot taken when the bubble opened; the outline is live. Only
            // outline a grey-framed button, so a recipe that turns deferred while the bubble is open
            // never shows the red-plus-yellow look (it gets the outline on the next open).
            if (fields.sprawlcrafting$isCraftable()
                    && RecipeBookHighlight.isDeferred(fields.sprawlcrafting$getRecipe())) {
                GuiButton button = (GuiButton) self;
                RecipeBookHighlight.drawOutline(button.x, button.y, button.width, button.height);
            }
        } catch (Throwable ignored) {
            // Decoration must never break the recipe book.
        }
    }
}
