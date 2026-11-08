package com.legoj15.sprawlcrafting.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.GatherCandidate;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.gui.recipebook.RecipeWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * Recipe-book button (Mojmap {@code RecipeButton}, Forge-1.16.5 {@code RecipeWidget}): swaps the slot
 * background to the yellow "deferred craftable" frame when the collection's craftable entries are
 * craftable only via deferral, and extends the tooltip with the deferred hint or the pending plan preview.
 *
 * <p>1.16.5 has no sprite atlas: vanilla blits a 25×25 cell out of {@code textures/gui/recipe_book.png}
 * at u=29 (craftable) / 54 (uncraftable), v=206 (single) / 231 (many). The redirect swaps the craftable
 * cells for our standalone 25×25 PNGs (the same art the modern sprites use) and rebinds the book texture.
 */
@Mixin(RecipeWidget.class)
public abstract class RecipeWidgetMixin implements GatherCandidate {

    private static final ResourceLocation RECIPE_BOOK_LOCATION = new ResourceLocation("textures/gui/recipe_book.png");
    private static final ResourceLocation SLOT_DEFERRED =
            Constants.id("textures/gui/sprites/recipe_book/slot_deferred_craftable.png");
    private static final ResourceLocation SLOT_MANY_DEFERRED =
            Constants.id("textures/gui/sprites/recipe_book/slot_many_deferred_craftable.png");
    private static final int CRAFTABLE_U = 29;
    private static final int MANY_V = 231;

    @Shadow
    private RecipeList collection;

    @Shadow
    public abstract boolean isOnlyOption();

    @Shadow
    public abstract IRecipe<?> getRecipe();

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/recipebook/RecipeWidget;blit(Lcom/mojang/blaze3d/matrix/MatrixStack;IIIIII)V"))
    private void sprawlcrafting$tintDeferredSlot(RecipeWidget self, MatrixStack poseStack, int x, int y,
                                                 int u, int v, int width, int height) {
        if (u == CRAFTABLE_U && sprawlcrafting$onlyDeferred()) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.getTextureManager().bind(v == MANY_V ? SLOT_MANY_DEFERRED : SLOT_DEFERRED);
            AbstractGui.blit(poseStack, x, y, 0.0F, 0.0F, width, height, 25, 25);
            minecraft.getTextureManager().bind(RECIPE_BOOK_LOCATION);
            return;
        }
        self.blit(poseStack, x, y, u, v, width, height);
    }

    @Inject(method = "getTooltipText", at = @At("RETURN"), cancellable = true)
    private void sprawlcrafting$appendDeferredTooltip(Screen screen, CallbackInfoReturnable<List<ITextComponent>> cir) {
        IRecipe<?> recipe = getRecipe();
        if (!DeferredCraftableCache.isDeferredOnly(recipe)) {
            if (recipe != null && !collection.isCraftable(recipe) && isOnlyOption()) {
                // Single red recipe: tell the player to right-click. Grouped recipes keep vanilla's
                // "more recipes" hint — there, right-click opens the variant overlay.
                sprawlcrafting$appendGatherHint(cir);
            }
            return;
        }
        List<ITextComponent> lines = new ArrayList<>(cir.getReturnValue());
        List<ITextComponent> preview = DeferredClickState.previewLinesFor(collection.getRecipes(false));
        if (preview.isEmpty()) {
            lines.add(new TranslationTextComponent("sprawlcrafting.preview.hint")
                    .withStyle(TextFormatting.YELLOW, TextFormatting.ITALIC));
        } else {
            lines.addAll(preview);
        }
        cir.setReturnValue(lines);
    }

    /** True when every craftable entry of this collection is deferred-only. */
    private boolean sprawlcrafting$onlyDeferred() {
        boolean any = false;
        for (IRecipe<?> recipe : collection.getRecipes(true)) {
            if (!DeferredCraftableCache.isDeferredOnly(recipe)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    @Override
    public boolean sprawlcrafting$tryOpenGatherList() {
        IRecipe<?> recipe = getRecipe();
        if (recipe == null || collection.isCraftable(recipe)) {
            return false; // craftable (incl. our deferred yellow): not a red gather candidate
        }
        if (!isOnlyOption()) {
            return false; // grouped recipe: leave right-click to vanilla's variant overlay
        }
        MissingIngredientsView.open(recipe, GridContext.CRAFTING_TABLE);
        return true;
    }

    private void sprawlcrafting$appendGatherHint(CallbackInfoReturnable<List<ITextComponent>> cir) {
        if (!SprawlConfig.get().needsSystem()) {
            return;
        }
        List<ITextComponent> lines = new ArrayList<>(cir.getReturnValue());
        lines.add(new TranslationTextComponent("sprawlcrafting.gather.rightclick")
                .withStyle(TextFormatting.GOLD, TextFormatting.ITALIC));
        cir.setReturnValue(lines);
    }
}
