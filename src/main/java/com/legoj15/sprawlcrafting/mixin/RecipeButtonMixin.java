package com.legoj15.sprawlcrafting.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.GatherCandidate;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
//? if >=1.21.11 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;*/
//?} else {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.crafting.RecipeHolder;
//?}

/**
 * Swaps the recipe button's slot background to the yellow "deferred craftable" sprite when the
 * collection's craftable entries are craftable only via deferral, and extends the button's own
 * tooltip with the deferred-craft hint or the pending plan preview.
 *
 * <p>1.21.1 keys on {@code RecipeHolder} and the immediate-mode {@code renderWidget}/GuiGraphics
 * draw; 26.x keys on {@code RecipeDisplayId} and the {@code extractWidgetRenderState}/
 * GuiGraphicsExtractor render-state model.
 */
@Mixin(RecipeButton.class)
public abstract class RecipeButtonMixin implements GatherCandidate {

    @Shadow
    private RecipeCollection collection;

    // Same signature on both MC versions (1.21.1: ordered recipes == 1; 26.x: selectedEntries == 1):
    // false when the button is a multi-recipe group, whose right-click vanilla owns (variant overlay).
    @Shadow
    public abstract boolean isOnlyOption();

    //? if >=1.21.11 {
    /*@Shadow
    public abstract net.minecraft.world.item.crafting.display.RecipeDisplayId getCurrentRecipe();*/
    //?} else {
    @Shadow
    public abstract RecipeHolder<?> getRecipe();
    //?}

    private static final ResourceLocation SLOT_DEFERRED =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recipe_book/slot_deferred_craftable");
    private static final ResourceLocation SLOT_MANY_DEFERRED =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recipe_book/slot_many_deferred_craftable");

    //? if >=1.21.11 {
    /*@WrapOperation(method = "extractWidgetRenderState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void sprawlcrafting$tintDeferredSlot(GuiGraphicsExtractor graphics, RenderPipeline pipeline,
            ResourceLocation sprite, int x, int y, int width, int height, Operation<Void> original) {
        ResourceLocation actual = sprite;
        if (sprite.getPath().equals("recipe_book/slot_craftable") && sprawlcrafting$onlyDeferred()) {
            actual = SLOT_DEFERRED;
        } else if (sprite.getPath().equals("recipe_book/slot_many_craftable") && sprawlcrafting$onlyDeferred()) {
            actual = SLOT_MANY_DEFERRED;
        }
        original.call(graphics, pipeline, actual, x, y, width, height);
    }

    @Inject(method = "getTooltipText(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private void sprawlcrafting$appendDeferredTooltip(ItemStack displayStack,
                                                      CallbackInfoReturnable<List<Component>> cir) {
        RecipeDisplayId recipe = getCurrentRecipe();
        if (!DeferredCraftableCache.isDeferredOnly(recipe)) {
            if (recipe != null && !collection.isCraftable(recipe) && isOnlyOption()) {
                // Single red recipe: tell the player to right-click. Grouped recipes keep vanilla's
                // "more recipes" hint — there, right-click opens the variant overlay, not the gather list.
                sprawlcrafting$appendGatherHint(cir);
            }
            return;
        }
        List<Component> lines = new java.util.ArrayList<>(cir.getReturnValue());
        List<RecipeDisplayId> ids = collection.getRecipes().stream().map(RecipeDisplayEntry::id).toList();
        List<Component> preview = DeferredClickState.previewLinesFor(ids);
        if (preview.isEmpty()) {
            lines.add(Component.translatable("sprawlcrafting.preview.hint")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        } else {
            lines.addAll(preview);
        }
        cir.setReturnValue(lines);
    }

    private boolean sprawlcrafting$onlyDeferred() {
        boolean any = false;
        for (RecipeDisplayEntry entry : collection.getSelectedRecipes(RecipeCollection.CraftableStatus.CRAFTABLE)) {
            if (!DeferredCraftableCache.isDeferredOnly(entry.id())) {
                return false;
            }
            any = true;
        }
        return any;
    }*/
    //?} else {
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

    @Inject(method = "getTooltipText", at = @At("RETURN"), cancellable = true)
    private void sprawlcrafting$appendDeferredTooltip(CallbackInfoReturnable<List<Component>> cir) {
        RecipeHolder<?> recipe = getRecipe();
        if (!DeferredCraftableCache.isDeferredOnly(recipe)) {
            if (recipe != null && !collection.isCraftable(recipe) && isOnlyOption()) {
                // Single red recipe: tell the player to right-click. Grouped recipes keep vanilla's
                // "more recipes" hint — there, right-click opens the variant overlay, not the gather list.
                sprawlcrafting$appendGatherHint(cir);
            }
            return;
        }
        List<Component> lines = new java.util.ArrayList<>(cir.getReturnValue());
        List<Component> preview = DeferredClickState.previewLinesFor(collection.getRecipes(false));
        if (preview.isEmpty()) {
            lines.add(Component.translatable("sprawlcrafting.preview.hint")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        } else {
            lines.addAll(preview);
        }
        cir.setReturnValue(lines);
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
    //?}

    @Override
    public boolean sprawlcrafting$tryOpenGatherList() {
        //? if >=1.21.11 {
        /*RecipeDisplayId id = getCurrentRecipe();
        if (id == null || collection.isCraftable(id)) {
            return false; // craftable (incl. our deferred yellow): not a red gather candidate
        }
        if (!isOnlyOption()) {
            return false; // grouped recipe: leave right-click to vanilla's variant overlay
        }
        MissingIngredientsView.open(id);
        return true;*/
        //?} else {
        RecipeHolder<?> holder = getRecipe();
        if (holder == null || collection.isCraftable(holder)) {
            return false; // craftable (incl. our deferred yellow): not a red gather candidate
        }
        if (!isOnlyOption()) {
            return false; // grouped recipe: leave right-click to vanilla's variant overlay
        }
        MissingIngredientsView.open(holder, GridContext.CRAFTING_TABLE);
        return true;
        //?}
    }

    /** Appends the "right-click to see what you need" hint to a red recipe's tooltip. */
    private void sprawlcrafting$appendGatherHint(CallbackInfoReturnable<List<Component>> cir) {
        if (!SprawlConfig.get().needsSystem()) {
            return; // gather/"needs" helper disabled: no right-click hint (right-click stays vanilla too)
        }
        List<Component> lines = new java.util.ArrayList<>(cir.getReturnValue());
        lines.add(Component.translatable("sprawlcrafting.gather.rightclick")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
        cir.setReturnValue(lines);
    }
}
