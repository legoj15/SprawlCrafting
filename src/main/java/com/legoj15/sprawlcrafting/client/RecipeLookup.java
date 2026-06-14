package com.legoj15.sprawlcrafting.client;

import net.minecraft.world.item.ItemStack;

/**
 * Optional bridge so the gather screen can open a recipe viewer's recipe/uses view for the hovered
 * item when R/U is pressed, without the screen hard-referencing the viewer (which may be absent). The
 * viewer's compat layer registers an implementation via {@link MissingIngredientsScreen#setRecipeLookup}.
 *
 * <p>Used for REI (whose key handling doesn't activate on a plain custom screen); JEI handles R/U
 * itself via the registered screen handler, so it needs no bridge.
 */
public interface RecipeLookup {

    /** Handle an R/U key press over {@code hovered}; return true if a view was opened (key consumed). */
    boolean handle(int keyCode, int scanCode, ItemStack hovered);
}
