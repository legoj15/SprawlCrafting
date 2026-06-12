package com.legoj15.sprawlcrafting.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;

@Mixin(RecipeCollection.class)
public interface RecipeCollectionAccessor {

    // 26.x rekeyed the recipe book on RecipeDisplayId; 1.21.1 used RecipeHolder.
    //? if >=1.21.11 {
    /*@Accessor("craftable")
    Set<net.minecraft.world.item.crafting.display.RecipeDisplayId> sprawlcrafting$craftable();*/
    //?} else {
    @Accessor("craftable")
    Set<RecipeHolder<?>> sprawlcrafting$craftable();
    //?}
}
