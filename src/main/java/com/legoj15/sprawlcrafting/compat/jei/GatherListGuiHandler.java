package com.legoj15.sprawlcrafting.compat.jei;

import java.util.Optional;

import com.legoj15.sprawlcrafting.client.MissingIngredientsScreen;

import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * Makes JEI's R/U (recipes/uses) keys work on items in the gather-list screen: reports the hovered
 * item as the ingredient under the mouse so JEI can look it up — handy when a missing item is made by
 * smelting or a modded machine rather than a crafting-table recipe. Registered as a global gui handler
 * because the gather screen is a plain {@code Screen}, not an {@code AbstractContainerScreen}.
 *
 * <p>The return type {@code Optional<IClickableIngredient<?>>} satisfies both JEI lines (19.27 declares
 * it exactly; 29.5 declares the wider {@code Optional<? extends …>}, which this is a valid override of),
 * so no version split is needed.
 */
public class GatherListGuiHandler implements IGlobalGuiHandler {

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(
            IClickableIngredientFactory factory, double mouseX, double mouseY) {
        if (!(Minecraft.getInstance().screen instanceof MissingIngredientsScreen screen)) {
            return Optional.empty();
        }
        ItemStack stack = screen.hoveredStack(mouseX, mouseY);
        Rect2i area = screen.hoveredArea(mouseX, mouseY);
        if (stack.isEmpty() || area == null) {
            return Optional.empty();
        }
        return factory.createBuilder(stack).buildWithArea(area)
                .map(ingredient -> (IClickableIngredient<?>) ingredient);
    }

    /**
     * Marks the gather screen as one JEI handles, which is what unblocks JEI's R/U key processing
     * (it gates on {@code getGuiProperties(screen).isPresent()}). The bounds span the whole screen so
     * JEI's ingredient-list overlay has no room and stays hidden — we only want the R/U lookup.
     */
    public static IGuiProperties guiProperties(MissingIngredientsScreen screen) {
        int w = screen.width;
        int h = screen.height;
        return new IGuiProperties() {
            @Override
            public Class<? extends Screen> screenClass() {
                return MissingIngredientsScreen.class;
            }

            @Override
            public int guiLeft() {
                return 0;
            }

            @Override
            public int guiTop() {
                return 0;
            }

            @Override
            public int guiXSize() {
                return w;
            }

            @Override
            public int guiYSize() {
                return h;
            }

            @Override
            public int screenWidth() {
                return w;
            }

            @Override
            public int screenHeight() {
                return h;
            }
        };
    }
}

