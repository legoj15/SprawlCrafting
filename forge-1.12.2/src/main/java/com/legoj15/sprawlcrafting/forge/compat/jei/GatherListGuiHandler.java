package com.legoj15.sprawlcrafting.forge.compat.jei;

import java.awt.Rectangle;
import java.util.Collection;
import java.util.Collections;

import javax.annotation.Nullable;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.client.GuiMissingResources;

import mezz.jei.api.gui.IGlobalGuiHandler;
import mezz.jei.api.gui.IGuiProperties;
import mezz.jei.api.gui.IGuiScreenHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

/**
 * Makes JEI first-class on the missing-resources gather screen (modern parity): the
 * {@link IGuiScreenHandler} half tells JEI the plain {@code GuiScreen} is a JEI-active screen
 * (item list panel, keybinds), and the {@link IGlobalGuiHandler} half exposes the demand item
 * under the mouse so R/U open its recipes/uses — "what do I need" flows straight into "how do I
 * make it". Both sides answer nothing when the needs helper is toggled off, which un-hooks the
 * screen live without re-registering anything.
 */
public class GatherListGuiHandler implements IGlobalGuiHandler, IGuiScreenHandler<GuiMissingResources> {

    @Override
    public Collection<Rectangle> getGuiExtraAreas() {
        return Collections.emptyList();
    }

    @Nullable
    @Override
    public Object getIngredientUnderMouse(int mouseX, int mouseY) {
        if (!SprawlConfig.needsSystem) {
            return null;
        }
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof GuiMissingResources)) {
            return null;
        }
        ItemStack stack = ((GuiMissingResources) screen).ingredientAt(mouseX, mouseY);
        return stack.isEmpty() ? null : stack;
    }

    @Nullable
    @Override
    public IGuiProperties apply(final GuiMissingResources gui) {
        if (!SprawlConfig.needsSystem) {
            return null;
        }
        return new IGuiProperties() {
            @Override
            public Class<? extends GuiScreen> getGuiClass() {
                return GuiMissingResources.class;
            }

            @Override
            public int getGuiLeft() {
                return gui.panelLeft();
            }

            @Override
            public int getGuiTop() {
                return gui.panelTop();
            }

            @Override
            public int getGuiXSize() {
                return gui.panelWidth();
            }

            @Override
            public int getGuiYSize() {
                return gui.panelHeight();
            }

            @Override
            public int getScreenWidth() {
                return gui.width;
            }

            @Override
            public int getScreenHeight() {
                return gui.height;
            }
        };
    }
}
