package com.legoj15.sprawlcrafting.forge.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.gui.recipebook.IRecipeShownListener;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Draws the two-click deferred-craft preview tooltip on top of everything. Hooked on
 * {@link GuiScreenEvent.DrawScreenEvent.Post} — which fires after the inventory slots, the right
 * panel, and vanilla tooltips have drawn — so the preview is no longer clipped behind them (the
 * problem with drawing it inside the recipe book's own render pass). Shown only while a recipe is
 * armed ({@link DeferredClickState}) and the recipe book is actually open.
 */
public final class DeferredPreviewRenderer {

    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!DeferredClickState.isArmed()) {
            return;
        }
        GuiScreen gui = event.getGui();
        if (!(gui instanceof IRecipeShownListener)) {
            return;
        }
        try {
            GuiRecipeBook book = ((IRecipeShownListener) gui).func_194310_f();
            if (book != null && book.isVisible()) {
                gui.drawHoveringText(DeferredClickState.previewLines(), event.getMouseX(), event.getMouseY());
            }
        } catch (Throwable ignored) {
            // Never let the preview break screen rendering.
        }
    }
}
