package com.legoj15.sprawlcrafting.compat.rei;

import com.legoj15.sprawlcrafting.client.RecipeLookup;

import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Opens REI's recipe (R) / uses (U) view for the hovered gather-list item. REI's own key handler
 * doesn't run on a plain custom screen, so {@code MissingIngredientsScreen} forwards the key here.
 * Matches R/U by keycode (REI's defaults) rather than reading the configurable keybind, to avoid
 * pulling cloth-config in. REI ships on the 1.21.1 nodes only.
 */
public class ReiRecipeLookup implements RecipeLookup {

    @Override
    public boolean handle(int keyCode, int scanCode, ItemStack hovered) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            ViewSearchBuilder.builder().addRecipesFor(EntryStacks.of(hovered)).open();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_U) {
            ViewSearchBuilder.builder().addUsagesFor(EntryStacks.of(hovered)).open();
            return true;
        }
        return false;
    }
}
