package com.legoj15.sprawlcrafting.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.client.GatherCandidate;
import com.legoj15.sprawlcrafting.config.SprawlConfig;

import net.minecraft.client.gui.recipebook.RecipeOverlayGui;
import net.minecraft.client.gui.widget.Widget;

/**
 * Variant overlay (Mojmap {@code OverlayRecipeComponent}, Forge-1.16.5 {@code RecipeOverlayGui}):
 * right-click a RED variant to open its gather list. Vanilla's overlay handles only left-click (any
 * other button returns false and the page closes the overlay), so this HEAD inject claims a right-click
 * that lands on a red variant first; the variant decides via {@link GatherCandidate}.
 */
@Mixin(RecipeOverlayGui.class)
public abstract class RecipeOverlayGuiMixin {

    private static final int MOUSE_BUTTON_RIGHT = 1;

    @Shadow
    @Final
    private List<? extends Widget> recipeButtons;

    @Shadow
    public abstract boolean isVisible();

    @Inject(method = "mouseClicked", cancellable = true, at = @At("HEAD"))
    private void sprawlcrafting$gatherOnRightClick(double mouseX, double mouseY, int button,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (button != MOUSE_BUTTON_RIGHT || !isVisible()) {
            return;
        }
        if (!SprawlConfig.get().needsSystem()) {
            return;
        }
        for (Widget widget : recipeButtons) {
            if (widget.isMouseOver(mouseX, mouseY) && widget instanceof GatherCandidate
                    && ((GatherCandidate) widget).sprawlcrafting$tryOpenGatherList()) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
