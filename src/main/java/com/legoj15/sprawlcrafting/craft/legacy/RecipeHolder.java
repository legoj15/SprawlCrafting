package com.legoj15.sprawlcrafting.craft.legacy;

// Pre-1.20.2 stand-in for vanilla's RecipeHolder (added in MC 1.20.2), compiled only on <1.20.2 nodes.
//
// The shared source is authored against 1.21.1, where a recipe travels as a RecipeHolder (id + value).
// 1.20.1 has no holder: a Recipe carries its own id via getId(). Rather than branching every
// signature that takes a holder, the <1.20.2 Stonecutter regex tier (stonecutter.gradle.kts) rewrites
// the vanilla RecipeHolder import to this class, so shared code keeps calling holder.id() /
// holder.value() unchanged. Only the version-specific edges — RecipeIds (RecipeManager lookups), the
// recipe-book mixins (vanilla hands them bare Recipe objects) and the JEI/REI handlers — wrap with
// of(recipe) or unwrap with value(). Equality is by id, exactly like vanilla's RecipeHolder.
//
// On >=1.20.2 nodes this file compiles to nothing (the whole class is gated off), so it can never
// shadow the vanilla type there.

//? if <1.20.2 {
/*import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

public record RecipeHolder<T extends Recipe<?>>(ResourceLocation id, T value) {

    public static <T extends Recipe<?>> RecipeHolder<T> of(T recipe) {
        return new RecipeHolder<>(recipe.getId(), recipe);
    }

    // Null-tolerant wrap, for vanilla getters that return null when nothing is selected.
    public static RecipeHolder<?> ofNullable(Recipe<?> recipe) {
        return recipe == null ? null : of(recipe);
    }

    // Wraps a vanilla recipe list (recipe-book collections hand out bare recipes on 1.20.1).
    public static java.util.List<RecipeHolder<?>> ofAll(java.util.List<? extends Recipe<?>> recipes) {
        java.util.List<RecipeHolder<?>> holders = new java.util.ArrayList<>(recipes.size());
        for (Recipe<?> recipe : recipes) {
            holders.add(of(recipe));
        }
        return holders;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof RecipeHolder<?> holder && id.equals(holder.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id.toString();
    }
}*/
//?}
