package com.legoj15.sprawlcrafting.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.client.GatherCandidate;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent;
//? if >=1.21.11 {
/*import net.minecraft.client.input.MouseButtonEvent;*/
//?}

/**
 * Right-click a RED variant in the recipe book's "more recipes" overlay to open its gather list.
 * Vanilla's overlay handles only left-click (it returns false for any other button, which makes the
 * page close the overlay), so this HEAD inject claims a right-click that lands on a red variant before
 * that happens. The variant decides via {@link GatherCandidate} ({@code OverlayRecipeButtonMixin}); a
 * right-click on empty space or a craftable/deferred variant returns normally and vanilla closes the
 * overlay as before.
 *
 * <p>1.21.1 takes raw {@code (mouseX, mouseY, button)}; 26.x takes a {@code MouseButtonEvent}.
 */
@Mixin(OverlayRecipeComponent.class)
public abstract class OverlayRecipeComponentMixin {

    @Shadow
    @Final
    private List<? extends AbstractWidget> recipeButtons;

    @Shadow
    public abstract boolean isVisible();

    //? if >=1.21.11 {
    /*@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
            cancellable = true, at = @At("HEAD"))
    private void sprawlcrafting$gatherOnRightClick(MouseButtonEvent event, boolean doubleClick,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 1 || !isVisible()) {
            return;
        }
        for (AbstractWidget button : recipeButtons) {
            if (button.isMouseOver(event.x(), event.y()) && button instanceof GatherCandidate gather
                    && gather.sprawlcrafting$tryOpenGatherList()) {
                cir.setReturnValue(true);
                return;
            }
        }
    }*/
    //?} else {
    @Inject(method = "mouseClicked(DDI)Z", cancellable = true, at = @At("HEAD"))
    private void sprawlcrafting$gatherOnRightClick(double mouseX, double mouseY, int button,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (button != 1 || !isVisible()) {
            return;
        }
        for (AbstractWidget widget : recipeButtons) {
            if (widget.isMouseOver(mouseX, mouseY) && widget instanceof GatherCandidate gather
                    && gather.sprawlcrafting$tryOpenGatherList()) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
    //?}
}
