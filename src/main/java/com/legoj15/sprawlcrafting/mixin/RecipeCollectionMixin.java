package com.legoj15.sprawlcrafting.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
//? if >=1.21.11 {
/*import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;*/
//?} else {
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.crafting.RecipeHolder;
//?}

/**
 * After vanilla decides which recipes in this collection are craftable from the grid +
 * inventory, additionally mark recipes that are solvable from raw resources as craftable.
 * This one hook makes the craftable-only filter, button sprites, overlay popup, and
 * {@code isCraftable} all agree; {@code DeferredCraftableCache} remembers which entries
 * were deferred-only so the button can tint them yellow instead of white.
 *
 * <p>1.21.1 computes solvability on the client in the {@code canCraft} pass. 26.x has no client
 * recipe graph, so it reads the server-synced deferred set (keyed by RecipeDisplayId) and marks
 * matching entries in the {@code selectRecipes} pass.
 */
@Mixin(RecipeCollection.class)
public abstract class RecipeCollectionMixin {

    //? if >=1.21.11 {
    /*@Inject(method = "selectRecipes(Lnet/minecraft/world/entity/player/StackedItemContents;Ljava/util/function/Predicate;)V",
            at = @At("TAIL"))
    private void sprawlcrafting$markDeferredCraftable(StackedItemContents contents,
                                                      java.util.function.Predicate<RecipeDisplay> selector,
                                                      CallbackInfo ci) {
        RecipeCollection self = (RecipeCollection) (Object) this;
        for (RecipeDisplayEntry entry : self.getRecipes()) {
            // Server already grid-scoped the deferred set; just mark entries it flagged.
            if (!self.isCraftable(entry.id()) && DeferredCraftableCache.isDeferredOnly(entry.id())) {
                ((RecipeCollectionAccessor) self).sprawlcrafting$craftable().add(entry.id());
            }
        }
    }*/
    //?} else {
    @Inject(method = "canCraft(Lnet/minecraft/world/entity/player/StackedContents;IILnet/minecraft/stats/RecipeBook;)V",
            at = @At("TAIL"))
    private void sprawlcrafting$markDeferredCraftable(StackedContents handler, int width, int height,
                                                      RecipeBook book, CallbackInfo ci) {
        if (width < 2 || height < 2) {
            return; // furnace-style books: deferred crafting doesn't apply
        }
        RecipeCollection self = (RecipeCollection) (Object) this;
        GridContext grid = DeferredCraftableCache.gridFor(width, height);
        for (RecipeHolder<?> holder : self.getRecipes(false)) {
            if (!self.isCraftable(holder) && DeferredCraftableCache.isSolvable(holder, grid)) {
                ((RecipeCollectionAccessor) self).sprawlcrafting$craftable().add(holder);
                DeferredCraftableCache.markDeferredOnly(holder);
            }
        }
    }
    //?}
}
