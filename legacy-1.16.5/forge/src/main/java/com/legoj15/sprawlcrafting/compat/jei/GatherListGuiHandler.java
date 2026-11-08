package com.legoj15.sprawlcrafting.compat.jei;

import com.legoj15.sprawlcrafting.client.MissingIngredientsScreen;

import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;

/**
 * Makes JEI's R/U (recipes/uses) keys work on items in the gather-list screen: reports the hovered
 * item as the ingredient under the mouse. Registered as a global handler because the gather screen is
 * a plain {@code Screen}, not a {@code ContainerScreen}.
 */
public class GatherListGuiHandler implements IGlobalGuiHandler {

    @Override
    public Object getIngredientUnderMouse(double mouseX, double mouseY) {
        Screen screen = Minecraft.getInstance().screen;
        if (!(screen instanceof MissingIngredientsScreen)) {
            return null;
        }
        ItemStack stack = ((MissingIngredientsScreen) screen).hoveredStack(mouseX, mouseY);
        return stack.isEmpty() ? null : stack;
    }

    /**
     * Marks the gather screen as one JEI handles, which unblocks JEI's R/U key processing. The bounds
     * span the whole screen so JEI's ingredient-list overlay has no room and stays hidden.
     */
    public static IGuiProperties guiProperties(MissingIngredientsScreen screen) {
        final int w = screen.width;
        final int h = screen.height;
        return new IGuiProperties() {
            @Override
            public Class<? extends Screen> getScreenClass() {
                return MissingIngredientsScreen.class;
            }

            @Override
            public int getGuiLeft() {
                return 0;
            }

            @Override
            public int getGuiTop() {
                return 0;
            }

            @Override
            public int getGuiXSize() {
                return w;
            }

            @Override
            public int getGuiYSize() {
                return h;
            }

            @Override
            public int getScreenWidth() {
                return w;
            }

            @Override
            public int getScreenHeight() {
                return h;
            }
        };
    }
}
