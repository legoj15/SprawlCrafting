package com.legoj15.sprawlcrafting.client;

//? if <1.21.11 {
import com.mojang.blaze3d.platform.Window;
//?}
import net.minecraft.client.Minecraft;

/**
 * Hit-test state for clicking the progress toast while a screen is open (issue #34): the toast
 * records its on-screen rectangle every frame it draws, and each loader's screen-click hook asks
 * {@link #onScreenClick}, which turns a left click on the toast into the same cancel request the
 * key sends — and consumes the click so the screen underneath never sees it.
 *
 * <p>The rectangle goes stale after 250 ms without a redraw: the toast stops drawing when the HUD
 * is hidden (F1) or it slides out, and its last position must not stay clickable.
 *
 * <p>The release of a consumed press still reaches the screen; a container screen's
 * {@code mouseReleased} with no prior press is a no-op in practice, so there is no release hook.
 */
public final class ToastCancelClick {

    /** A recorded rectangle is trusted for this long without a redraw. */
    private static final long STALE_NANOS = 250_000_000L;

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
     * screen. Left button only, and only when the key's own gate passes.
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
        if (Minecraft.getInstance().screen == null || !CancelCraftKey.canCancel()) {
            return false;
        }
        double[] mouse = mouseOver();
        return isOver(mouse[0], mouse[1]);
    }

    /**
     * The cursor in GUI-scaled coordinates — the values a screen's {@code mouseClicked} receives,
     * converted from the raw cursor because no screen callback hands them to the toast.
     */
    private static double[] mouseOver() {
        Minecraft mc = Minecraft.getInstance();
        //? if >=1.21.11 {
        /*return new double[] {mc.mouseHandler.getScaledXPos(mc.getWindow()),
                mc.mouseHandler.getScaledYPos(mc.getWindow())};*/
        //?} else {
        Window window = mc.getWindow();
        return new double[] {
                mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth(),
                mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight()};
        //?}
    }
}
