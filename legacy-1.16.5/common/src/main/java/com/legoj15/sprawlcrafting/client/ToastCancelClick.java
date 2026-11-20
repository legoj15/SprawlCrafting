package com.legoj15.sprawlcrafting.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.Minecraft;

/**
 * Hit-test state for clicking the progress toast while a screen is open (issue #34): the toast
 * records its on-screen rectangle every frame it draws, and each loader's screen-click hook asks
 * {@link #onScreenClick}, which turns a left click on the toast into the same cancel request the
 * keybind sends — and consumes the click so the screen underneath never sees it.
 *
 * <p>The rectangle goes stale after 250 ms without a redraw: the toast stops drawing when the HUD
 * is hidden (F1) or slides out, and its last position must not stay clickable.
 *
 * <p>The release of a consumed press still reaches the screen; a container screen's
 * {@code mouseReleased} with no prior press is a no-op in practice, so there is no release hook.
 */
public final class ToastCancelClick {

    /** A recorded rectangle is trusted for this long without a redraw. */
    private static final long STALE_NANOS = 250_000_000L;

    /** {@code GL11.GL_MODELVIEW_MATRIX}. */
    private static final int GL_MODELVIEW_MATRIX = 0x0BA6;

    /**
     * Scratch for reading the fixed-function modelview: 1.16.5's ToastGui translates the toast
     * into place on the GL stack, not on the (identity) MatrixStack it hands the toast.
     */
    private static final FloatBuffer MODEL_VIEW =
            ByteBuffer.allocateDirect(16 * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private static float x;
    private static float y;
    private static int width;
    private static int height;
    private static long recordedAt;

    private ToastCancelClick() {
    }

    /** Called by the toast every frame it draws, with the corner its transform places it at. */
    public static void record(float toastX, float toastY, int toastWidth, int toastHeight) {
        x = toastX;
        y = toastY;
        width = toastWidth;
        height = toastHeight;
        recordedAt = System.nanoTime();
    }

    /**
     * Records from the current GL modelview translation: ToastGui pushes/translates the global
     * fixed-function matrix to the toast's on-screen corner before drawing (the rest of the
     * overlay modelview is only the ortho setup's z offset), so its x/y ARE GUI coordinates.
     * The MatrixStack passed to the toast could add its own translation, but 1.16.5's Matrix4f
     * fields are private and vanilla hands the toast a fresh identity — so there is nothing to add.
     */
    public static void recordGl(int toastWidth, int toastHeight) {
        MODEL_VIEW.clear();
        // Vanilla's wrapper, not LWJGL directly: the Forge build's compile classpath has no LWJGL.
        GlStateManager._getMatrix(GL_MODELVIEW_MATRIX, MODEL_VIEW);
        record(MODEL_VIEW.get(12), MODEL_VIEW.get(13), toastWidth, toastHeight);
    }

    /** Drops the rectangle — called when the toast hides and on disconnect. */
    public static void clear() {
        recordedAt = 0L;
    }

    /** True inside the recorded rectangle, and only while it is still fresh. */
    public static boolean isOver(double mouseX, double mouseY) {
        if (System.nanoTime() - recordedAt > STALE_NANOS) {
            return false;
        }
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * A screen click asks this; true means the click cancelled the job and must not reach the
     * screen. Left button only, and only when the keybind's own gate passes.
     */
    public static boolean onScreenClick(double mouseX, double mouseY, int button) {
        if (button != 0 || !isOver(mouseX, mouseY) || !CancelCraftKey.canCancel()) {
            return false;
        }
        CancelCraftKey.sendCancel();
        return true;
    }

    /**
     * Whether the toast should draw its "Click to cancel" hint: a screen is open (cursor free),
     * a click would actually send the cancel, and the mouse sits inside the toast.
     */
    public static boolean mouseOverHint() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null || !CancelCraftKey.canCancel()) {
            return false;
        }
        return isOver(mouseX(mc), mouseY(mc));
    }

    /**
     * The cursor in GUI-scaled coordinates — the values a screen's {@code mouseClicked} receives,
     * converted from the raw cursor because no screen callback hands them to the toast.
     */
    private static double mouseX(Minecraft mc) {
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
    }

    private static double mouseY(Minecraft mc) {
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
    }
}
