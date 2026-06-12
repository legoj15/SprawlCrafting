package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.minecraft.client.gui.Font;
//? if >=1.21.11 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;*/
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.toasts.Toast;
//? if >=1.21.11 {
/*import net.minecraft.client.gui.components.toasts.ToastManager;*/
//?} else {
import net.minecraft.client.gui.components.toasts.ToastComponent;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The top-right flyout showing the active deferred craft: current component's icon, the
 * job target, a count, and a progress bar. Persists (the toast slot stays occupied) for
 * the whole job, updating live from {@link ClientJobTracker}; terminal states linger
 * briefly, then the toast slides out.
 *
 * <p>1.21.1 drives lifecycle + drawing from a single {@code render()} call that returns the
 * wanted visibility. 26.x (>=1.21.11) reworked the Toast interface into a render-state model:
 * lifecycle is decided in {@code update()} (and reported by {@code getWantedVisibility()}),
 * while {@code extractRenderState()} only draws — so the terminal-linger/hide side effects
 * move into {@code update()} there.
 */
public class CraftProgressToast implements Toast {

    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/recipe");

    private static final int TITLE_COLOR = 0xFF332211;
    private static final int SUBTITLE_COLOR = 0xFF6B5B4A;
    // Darkened from goldenrod for ~4.8:1 contrast on the light (0xDEDEDE) toast interior —
    // PAUSED is the long-lived, must-read state, so legibility matters most here.
    private static final int PAUSED_COLOR = 0xFF7A5900;
    private static final int FINISHED_COLOR = 0xFF1C661C;
    private static final int CANCELLED_COLOR = 0xFFAA2020;
    private static final int BAR_COLOR = 0xFF76C610;
    private static final int BAR_BACK_COLOR = 0xFF3F3324;

    /** Text budget inside the 160px sprite (interior ends ~x=156, text starts at x=30). */
    private static final int TEXT_X = 30;
    private static final int TEXT_BUDGET = 124;

    //? if >=1.21.11 {
    /*private Toast.Visibility wantedVisibility = Toast.Visibility.SHOW;

    @Override
    public Toast.Visibility getWantedVisibility() {
        return this.wantedVisibility;
    }

    @Override
    public void update(ToastManager toastManager, long timeSinceLastVisible) {
        CraftProgressPayload job = ClientJobTracker.current();
        if (job == null || ClientJobTracker.terminalDisplayElapsed()) {
            ClientJobTracker.onToastHidden();
            this.wantedVisibility = Toast.Visibility.HIDE;
        } else {
            this.wantedVisibility = Toast.Visibility.SHOW;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, Font font, long timeSinceLastVisible) {
        CraftProgressPayload job = ClientJobTracker.current();
        if (job == null) {
            return; // update() will have flagged us HIDE; nothing to draw
        }

        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), height());

        ItemStack icon = job.current().isEmpty() ? job.target() : job.current();
        guiGraphics.fakeItem(icon, 8, 8);

        switch (job.state()) {
            case CRAFTING -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                guiGraphics.text(font, Component.translatable("sprawlcrafting.toast.step",
                        job.done(), job.total()), TEXT_X, 18, SUBTITLE_COLOR, false);
                drawProgressBar(guiGraphics, job);
            }
            case PAUSED -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                guiGraphics.text(font, Component.translatable("sprawlcrafting.toast.paused"),
                        TEXT_X, 18, PAUSED_COLOR, false);
                drawProgressBar(guiGraphics, job);
            }
            case FINISHED -> drawClipped(guiGraphics, font,
                    Component.translatable("sprawlcrafting.toast.finished", job.target().getHoverName()),
                    12, FINISHED_COLOR);
            case CANCELLED -> guiGraphics.text(font,
                    Component.translatable("sprawlcrafting.toast.cancelled"),
                    TEXT_X, 12, CANCELLED_COLOR, false);
        }
    }

    private void drawClipped(GuiGraphicsExtractor guiGraphics, Font font, Component text, int y, int color) {
        String string = text.getString();
        if (font.width(string) > TEXT_BUDGET) {
            string = font.plainSubstrByWidth(string, TEXT_BUDGET - font.width("...")) + "...";
        }
        guiGraphics.text(font, string, TEXT_X, y, color, false);
    }

    private void drawProgressBar(GuiGraphicsExtractor guiGraphics, CraftProgressPayload job) {
        int left = 30;
        int right = width() - 8;
        int y = 27;
        guiGraphics.fill(left, y, right, y + 2, BAR_BACK_COLOR);
        if (job.total() > 0) {
            int filled = (int) ((right - left) * (float) job.done() / job.total());
            guiGraphics.fill(left, y, left + filled, y + 2, BAR_COLOR);
        }
    }*/
    //?} else {
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
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.step",
                        job.done(), job.total()), TEXT_X, 18, SUBTITLE_COLOR, false);
                drawProgressBar(guiGraphics, job);
            }
            case PAUSED -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.paused"),
                        TEXT_X, 18, PAUSED_COLOR, false);
                drawProgressBar(guiGraphics, job);
            }
            case FINISHED -> drawClipped(guiGraphics, font,
                    Component.translatable("sprawlcrafting.toast.finished", job.target().getHoverName()),
                    12, FINISHED_COLOR);
            case CANCELLED -> guiGraphics.drawString(font,
                    Component.translatable("sprawlcrafting.toast.cancelled"),
                    TEXT_X, 12, CANCELLED_COLOR, false);
        }
        return Toast.Visibility.SHOW;
    }

    /** Draws text, ellipsizing to the toast's interior width so long item names don't overrun. */
    private void drawClipped(GuiGraphics guiGraphics, Font font, Component text, int y, int color) {
        String string = text.getString();
        if (font.width(string) > TEXT_BUDGET) {
            string = font.plainSubstrByWidth(string, TEXT_BUDGET - font.width("...")) + "...";
        }
        guiGraphics.drawString(font, string, TEXT_X, y, color, false);
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
    //?}
}
