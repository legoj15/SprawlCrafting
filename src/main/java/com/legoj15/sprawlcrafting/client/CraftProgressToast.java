package com.legoj15.sprawlcrafting.client;

import com.legoj15.solver.CountFormat;
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
 * <p>While any screen is open, the toast is also clickable (issue #34): every frame it draws it
 * records its on-screen rectangle in {@link ToastCancelClick} — a left click on it cancels the
 * job — and while the cursor hovers it, the second line becomes a "Click to cancel" hint, only
 * in the live states and only when a click would actually send the cancel.
 *
 * <p>1.21.1 drives lifecycle + drawing from a single {@code render()} call that returns the
 * wanted visibility. 26.x (>=1.21.11) reworked the Toast interface into a render-state model:
 * lifecycle is decided in {@code update()} (and reported by {@code getWantedVisibility()}),
 * while {@code extractRenderState()} only draws — so the terminal-linger/hide side effects
 * (and the click rectangle's clear) move into {@code update()} there.
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

    /** "Need a crafting table!" or, for a job waiting on its crafting station, "Paused — open station". */
    private static String pausedKey(CraftProgressPayload.State state) {
        return state == CraftProgressPayload.State.PAUSED_STATION
                ? "sprawlcrafting.toast.paused_station" : "sprawlcrafting.toast.paused";
    }

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
            ToastCancelClick.clear();
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

        // The toast is drawn under a translate to its screen position (ToastManager.ToastInstance
        // .extractRenderState), so the pose's translation IS that position — 26.x hands us a
        // Matrix3x2fStack, whose m20/m21 carry it. Record it for the click hit-test.
        org.joml.Matrix3x2fStack pose = guiGraphics.pose();
        ToastCancelClick.record(pose.m20(), pose.m21(), width(), height());
        boolean cancelHint = ToastCancelClick.mouseOverHint();

        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), height());

        ItemStack icon = job.current().isEmpty() ? job.target() : job.current();
        guiGraphics.fakeItem(icon, 8, 8);

        switch (job.state()) {
            case CRAFTING -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                if (cancelHint) {
                    guiGraphics.text(font, Component.translatable("sprawlcrafting.toast.click_to_cancel"),
                            TEXT_X, 18, CANCELLED_COLOR, false);
                } else {
                    guiGraphics.text(font, Component.translatable("sprawlcrafting.toast.step",
                            CountFormat.format(job.done()), CountFormat.format(job.total())), TEXT_X, 18, SUBTITLE_COLOR, false);
                }
                drawProgressBar(guiGraphics, job);
            }
            case PAUSED, PAUSED_STATION -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                if (cancelHint) {
                    guiGraphics.text(font, Component.translatable("sprawlcrafting.toast.click_to_cancel"),
                            TEXT_X, 18, CANCELLED_COLOR, false);
                } else {
                    guiGraphics.text(font, Component.translatable(pausedKey(job.state())),
                            TEXT_X, 18, PAUSED_COLOR, false);
                }
                drawProgressBar(guiGraphics, job);
            }
            case FINISHED -> drawClipped(guiGraphics, font,
                    Component.translatable("sprawlcrafting.toast.finished", job.target().getHoverName()),
                    12, FINISHED_COLOR);
            case CANCELLED -> guiGraphics.text(font,
                    Component.translatable("sprawlcrafting.toast.cancelled"),
                    TEXT_X, 12, CANCELLED_COLOR, false);
            case READY_IN_GRID -> drawClipped(guiGraphics, font,
                    Component.translatable("sprawlcrafting.toast.ready_in_grid", job.target().getHoverName()),
                    12, FINISHED_COLOR);
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
            // Ratio in double from the long counts (#46): int math would overflow on a job past
            // Integer.MAX_VALUE crafts long before the cast.
            int filled = (int) ((right - left) * ((double) job.done() / job.total()));
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
            ToastCancelClick.clear();
            return Toast.Visibility.HIDE;
        }

        // The toast is drawn under a translate to its screen position (ToastComponent.ToastInstance
        // .render), so the pose's translation IS that position (m30/m31 of its Matrix4f). Record it
        // for the click hit-test.
        org.joml.Matrix4f pose = guiGraphics.pose().last().pose();
        ToastCancelClick.record(pose.m30(), pose.m31(), width(), height());
        boolean cancelHint = ToastCancelClick.mouseOverHint();

        //? if >=1.20.2 {
        guiGraphics.blitSprite(BACKGROUND_SPRITE, 0, 0, width(), height());
        //?} else {
        /*// Pre-sprite-atlas GUI: the recipe-toast background is the (0, 32) cell of toasts.png,
        // exactly what vanilla's 1.20.1 RecipeToast blits.
        guiGraphics.blit(Toast.TEXTURE, 0, 0, 0, 32, width(), height());*/
        //?}
        Font font = toastComponent.getMinecraft().font;

        ItemStack icon = job.current().isEmpty() ? job.target() : job.current();
        guiGraphics.renderFakeItem(icon, 8, 8);

        switch (job.state()) {
            case CRAFTING -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                if (cancelHint) {
                    guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.click_to_cancel"),
                            TEXT_X, 18, CANCELLED_COLOR, false);
                } else {
                    guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.step",
                            CountFormat.format(job.done()), CountFormat.format(job.total())), TEXT_X, 18, SUBTITLE_COLOR, false);
                }
                drawProgressBar(guiGraphics, job);
            }
            case PAUSED, PAUSED_STATION -> {
                drawClipped(guiGraphics, font, Component.translatable("sprawlcrafting.toast.crafting",
                        job.target().getHoverName()), 7, TITLE_COLOR);
                if (cancelHint) {
                    guiGraphics.drawString(font, Component.translatable("sprawlcrafting.toast.click_to_cancel"),
                            TEXT_X, 18, CANCELLED_COLOR, false);
                } else {
                    guiGraphics.drawString(font, Component.translatable(pausedKey(job.state())),
                            TEXT_X, 18, PAUSED_COLOR, false);
                }
                drawProgressBar(guiGraphics, job);
            }
            case FINISHED -> drawClipped(guiGraphics, font,
                    Component.translatable("sprawlcrafting.toast.finished", job.target().getHoverName()),
                    12, FINISHED_COLOR);
            case CANCELLED -> guiGraphics.drawString(font,
                    Component.translatable("sprawlcrafting.toast.cancelled"),
                    TEXT_X, 12, CANCELLED_COLOR, false);
            case READY_IN_GRID -> drawClipped(guiGraphics, font,
                    Component.translatable("sprawlcrafting.toast.ready_in_grid", job.target().getHoverName()),
                    12, FINISHED_COLOR);
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
            // Ratio in double from the long counts (#46): int math would overflow on a job past
            // Integer.MAX_VALUE crafts long before the cast.
            int filled = (int) ((right - left) * ((double) job.done() / job.total()));
            guiGraphics.fill(left, y, left + filled, y + 2, BAR_COLOR);
        }
    }
    //?}
}
