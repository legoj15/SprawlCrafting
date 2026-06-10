package com.legoj15.sprawlcrafting.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.RecipeHolder;

@Mixin(RecipeCollection.class)
public interface RecipeCollectionAccessor {

    @Accessor("craftable")
    Set<RecipeHolder<?>> sprawlcrafting$craftable();
}
