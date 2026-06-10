package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Swaps the recipe button's slot background to the yellow "deferred craftable" sprite
 * when the collection's craftable entries are craftable only via deferral — vanilla
 * white means craft-it-right-now, our yellow means craftable-from-raws.
 */
@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin {

    @Shadow
    private RecipeCollection collection;

    private static final ResourceLocation SLOT_DEFERRED =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recipe_book/slot_deferred_craftable");
    private static final ResourceLocation SLOT_MANY_DEFERRED =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recipe_book/slot_many_deferred_craftable");

    @WrapOperation(method = "renderWidget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void sprawlcrafting$tintDeferredSlot(GuiGraphics guiGraphics, ResourceLocation sprite,
                                                 int x, int y, int width, int height, Operation<Void> original) {
        ResourceLocation actual = sprite;
        if (sprite.getPath().equals("recipe_book/slot_craftable") && sprawlcrafting$onlyDeferred()) {
            actual = SLOT_DEFERRED;
        } else if (sprite.getPath().equals("recipe_book/slot_many_craftable") && sprawlcrafting$onlyDeferred()) {
            actual = SLOT_MANY_DEFERRED;
        }
        original.call(guiGraphics, actual, x, y, width, height);
    }

    /** True when every craftable entry of this collection is deferred-only. */
    private boolean sprawlcrafting$onlyDeferred() {
        boolean any = false;
        for (RecipeHolder<?> holder : collection.getRecipes(true)) {
            if (!DeferredCraftableCache.isDeferredOnly(holder)) {
                return false;
            }
            any = true;
        }
        return any;
    }
}
