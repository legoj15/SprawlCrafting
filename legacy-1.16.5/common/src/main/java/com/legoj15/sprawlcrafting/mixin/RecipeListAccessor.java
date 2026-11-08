package com.legoj15.sprawlcrafting.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.item.crafting.IRecipe;

@Mixin(RecipeList.class)
public interface RecipeListAccessor {

    @Accessor("craftable")
    Set<IRecipe<?>> sprawlcrafting$craftable();
}
