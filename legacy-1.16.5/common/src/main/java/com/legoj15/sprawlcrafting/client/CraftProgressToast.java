package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.craft.solver.CountFormat;
import com.legoj15.sprawlcrafting.network.CraftProgressPacket;
import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.gui.toasts.ToastGui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * The top-right flyout showing the active deferred craft: current component's icon, the job target, a
 * count, and a progress bar. Persists for the whole job, updating live from {@link ClientJobTracker};
 * terminal states linger briefly, then the toast slides out. Drawn on vanilla's recipe-toast frame
 * ({@code textures/gui/toasts.png}, v=32), the 1.16.5 equivalent of modern's {@code toast/recipe} sprite.
 */
public class CraftProgressToast implements IToast {

    private static final int TITLE_COLOR = 0xFF332211;
    private static final int SUBTITLE_COLOR = 0xFF6B5B4A;
    private static final int PAUSED_COLOR = 0xFF7A5900;
    private static final int FINISHED_COLOR = 0xFF1C661C;
    private static final int CANCELLED_COLOR = 0xFFAA2020;
    private static final int BAR_COLOR = 0xFF76C610;
    private static final int BAR_BACK_COLOR = 0xFF3F3324;

    /** Text budget inside the 160px frame (interior ends ~x=156, text starts at x=30). */
    private static final int TEXT_X = 30;
    private static final int TEXT_BUDGET = 124;

    @Override
    public IToast.Visibility render(MatrixStack poseStack, ToastGui toastGui, long timeSinceLastVisible) {
        CraftProgressPacket job = ClientJobTracker.current();
        if (job == null || ClientJobTracker.terminalDisplayElapsed()) {
            ClientJobTracker.onToastHidden();
            return IToast.Visibility.HIDE;
        }

        toastGui.getMinecraft().getTextureManager().bind(TEXTURE);
        toastGui.blit(poseStack, 0, 0, 0, 32, width(), height());
        FontRenderer font = toastGui.getMinecraft().font;

        ItemStack icon = job.current().isEmpty() ? job.target() : job.current();
        toastGui.getMinecraft().getItemRenderer().renderAndDecorateFakeItem(icon, 8, 8);

        switch (job.state()) {
            case CRAFTING:
                drawClipped(poseStack, font, new TranslationTextComponent("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                font.draw(poseStack, new TranslationTextComponent("sprawlcrafting.toast.step",
                        CountFormat.format(job.done()), CountFormat.format(job.total())), TEXT_X, 18, SUBTITLE_COLOR);
                drawProgressBar(poseStack, job);
                break;
            case PAUSED:
                drawClipped(poseStack, font, new TranslationTextComponent("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                font.draw(poseStack, new TranslationTextComponent("sprawlcrafting.toast.paused"),
                        TEXT_X, 18, PAUSED_COLOR);
                drawProgressBar(poseStack, job);
                break;
            case FINISHED:
                drawClipped(poseStack, font, new TranslationTextComponent("sprawlcrafting.toast.finished",
                        job.target().getHoverName()), 12, FINISHED_COLOR);
                break;
            case CANCELLED:
                font.draw(poseStack, new TranslationTextComponent("sprawlcrafting.toast.cancelled"),
                        TEXT_X, 12, CANCELLED_COLOR);
                break;
            case READY_IN_GRID:
            default:
                drawClipped(poseStack, font, new TranslationTextComponent("sprawlcrafting.toast.ready_in_grid",
                        job.target().getHoverName()), 12, FINISHED_COLOR);
                break;
        }
        return IToast.Visibility.SHOW;
    }

    /** Draws text, ellipsizing to the toast's interior width so long item names don't overrun. */
    private void drawClipped(MatrixStack poseStack, FontRenderer font, ITextComponent text, int y, int color) {
        String string = text.getString();
        if (font.width(string) > TEXT_BUDGET) {
            string = font.plainSubstrByWidth(string, TEXT_BUDGET - font.width("...")) + "...";
        }
        font.draw(poseStack, string, TEXT_X, y, color);
    }

    private void drawProgressBar(MatrixStack poseStack, CraftProgressPacket job) {
        int left = 30;
        int right = width() - 8;
        int y = 27;
        AbstractGui.fill(poseStack, left, y, right, y + 2, BAR_BACK_COLOR);
        if (job.total() > 0) {
            int filled = (int) ((right - left) * (float) job.done() / job.total());
            AbstractGui.fill(poseStack, left, y, left + filled, y + 2, BAR_COLOR);
        }
    }
}
