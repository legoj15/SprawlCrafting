package com.legoj15.sprawlcrafting.forge.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.gui.recipebook.IRecipeShownListener;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Draws the two-click deferred-craft preview tooltip for the recipe book's alternates bubble, and
 * only while the mouse is over the armed bubble button (the per-frame hover mark
 * {@code GuiRecipeOverlayButtonMixin} sets and {@link DeferredClickState#consumeOverlayHover()}
 * reads). Page buttons need no renderer: their preview rides in the button's own tooltip
 * ({@code GuiButtonRecipeMixin}), which vanilla already draws only on hover.
 *
 * <p>Hooked on {@link GuiScreenEvent.DrawScreenEvent.Post} — which fires after the inventory
 * slots, the right panel, and vanilla tooltips have drawn — because the bubble is drawn inside the
 * recipe book's own render pass, before the container, which would clip a tooltip drawn there.
 */
public final class DeferredPreviewRenderer {

    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        // Consume unconditionally: the mark is per-frame, and one left over must never show the
        // preview in a later frame the mouse is elsewhere.
        boolean overlayHover = DeferredClickState.consumeOverlayHover();
        if (!overlayHover) {
            return;
        }
        GuiScreen gui = event.getGui();
        if (!(gui instanceof IRecipeShownListener)) {
            return;
        }
        try {
            GuiRecipeBook book = ((IRecipeShownListener) gui).func_194310_f();
            if (book != null && book.isVisible()) {
                List<String> preview = DeferredClickState.previewLines();
                if (!preview.isEmpty()) {
                    // A copy: the cached preview is unmodifiable, and drawHoveringText hands its
                    // list to RenderTooltipEvent listeners, which may edit it.
                    gui.drawHoveringText(new ArrayList<String>(preview),
                            event.getMouseX(), event.getMouseY());
                }
            }
        } catch (Throwable ignored) {
            // Never let the preview break screen rendering.
        }
    }
}
