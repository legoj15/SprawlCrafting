package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The top-right flyout showing the active deferred craft: current component's icon, the
 * job target, a count, and a progress bar. Persists (the toast slot stays occupied) for
 * the whole job, updating live from {@link ClientJobTracker}; terminal states linger
 * briefly, then the toast slides out.
 */
public class CraftProgressToast implements Toast {

    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/recipe");

    private static final int TITLE_COLOR = 0xFF332211;
    private static final int SUBTITLE_COLOR = 0xFF6B5B4A;
    private static final int PAUSED_COLOR = 0xFFB8860B;
    private static final int FINISHED_COLOR = 0xFF207A20;
    private static final int CANCELLED_COLOR = 0xFFAA2020;
    private static final int BAR_COLOR = 0xFF76C610;
    private static final int BAR_BACK_COLOR = 0xFF3F3324;

    @Override
    public Toast.Visibility render(GuiGraphics guiGraphics, ToastComponent toastComponent,
                                   long timeSinceLastVisible) {
        CraftProgressPayload job = ClientJobTracker.current();
        if (job == null || ClientJobTracker.terminalDisplayElapsed()) {
            ClientJobTracker.onToastHidden();
            return Toast.Visibility.HIDE;
        }

        guiGraphics.blitSprite(BACKGROUND_SPRITE, 0, 0, width(), height());
        Font font = toastComponent.getMinecraft().font;

        ItemStack icon = job.current().isEmpty() ? job.target() : job.current();
        guiGraphics.renderFakeItem(icon, 8, 8);

        switch (job.state()) {
            case CRAFTING -> {
                guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 30, 7, TITLE_COLOR, false);
                guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.step",
                        job.done(), job.total()), 30, 18, SUBTITLE_COLOR, false);
                drawProgressBar(guiGraphics, job);
            }
            case PAUSED -> {
                guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 30, 7, TITLE_COLOR, false);
                guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.paused"),
                        30, 18, PAUSED_COLOR, false);
                drawProgressBar(guiGraphics, job);
            }
            case FINISHED -> guiGraphics.drawString(font,
                    Component.translatable("sprawlcrafting.toast.finished", job.target().getHoverName()),
                    30, 12, FINISHED_COLOR, false);
            case CANCELLED -> guiGraphics.drawString(font,
                    Component.translatable("sprawlcrafting.toast.cancelled"),
                    30, 12, CANCELLED_COLOR, false);
        }
        return Toast.Visibility.SHOW;
    }

    private void drawProgressBar(GuiGraphics guiGraphics, CraftProgressPayload job) {
        int left = 30;
        int right = width() - 8;
        int y = 27;
        guiGraphics.fill(left, y, right, y + 2, BAR_BACK_COLOR);
        if (job.total() > 0) {
            int filled = (int) ((right - left) * (float) job.done() / job.total());
            guiGraphics.fill(left, y, left + filled, y + 2, BAR_COLOR);
        }
    }
}
