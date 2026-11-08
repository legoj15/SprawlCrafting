package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.ShortfallView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.item.crafting.IRecipe;

/**
 * Opens the {@link MissingIngredientsScreen} from a trigger site (the JEI transfer button, or a
 * right-clicked red recipe-book entry), keeping the underlying container screen as the parent.
 */
public final class MissingIngredientsView {

    private MissingIngredientsView() {
    }

    /**
     * The screen the gather list returns to on ESC: never a transient recipe viewer, always the
     * underlying container — the current screen if it is one, else the last container the watcher saw.
     */
    private static Screen resolveParent(Minecraft mc) {
        if (mc.screen instanceof ContainerScreen) {
            return mc.screen;
        }
        Screen remembered = CraftingScreenWatcher.lastContainerScreen();
        return remembered != null ? remembered : mc.screen;
    }

    public static void open(IRecipe<?> recipe, GridContext grid) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = resolveParent(mc);
        ShortfallView view = MissingIngredients.shortfallLocal(recipe, grid);
        mc.setScreen(new MissingIngredientsScreen(parent, view));
    }
}
