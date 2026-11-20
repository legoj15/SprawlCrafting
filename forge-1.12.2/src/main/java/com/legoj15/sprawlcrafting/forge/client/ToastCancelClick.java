package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import java.nio.FloatBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Hit-test state for clicking the progress toast while a screen is open (issue #34): the toast
 * records its on-screen rectangle every frame it draws, and {@link Handler} turns a left click on
 * the toast into the same cancel request the keybind sends — consuming the click so the screen
 * underneath never sees it.
 *
 * <p>The rectangle goes stale after 250 ms without a redraw: the toast stops drawing when it hides
 * (or the connection drops), and its last position must not stay clickable.
 *
 * <p>A consumed press's release is swallowed too ({@link #onScreenRelease}): a container screen
 * treats a lone release outside its panel as a click at slot -999, which drops a carried stack.
 */
public final class ToastCancelClick {

    /** A recorded rectangle is trusted for this long without a redraw. */
    private static final long STALE_NANOS = 250_000_000L;

    /**
     * Scratch for reading the fixed-function modelview: GuiToast translates the toast into place
     * on the GL stack (there is no MatrixStack in 1.12.2's toast draw).
     */
    private static final FloatBuffer MODEL_VIEW = BufferUtils.createFloatBuffer(16);

    private static float x;
    private static float y;
    private static int width;
    private static int height;
    private static long recordedAt;
    private static boolean recorded;
    /** The last left press was the toast's, so its release must not reach the screen either. */
    private static boolean swallowRelease;

    private ToastCancelClick() {
    }

    /** Called by the toast every frame it draws, with the corner its transform places it at. */
    public static void record(float toastX, float toastY, int toastWidth, int toastHeight) {
        x = toastX;
        y = toastY;
        width = toastWidth;
        height = toastHeight;
        recordedAt = System.nanoTime();
        recorded = true;
    }

    /**
     * Records from the current GL modelview translation: GuiToast pushes/translates the
     * fixed-function matrix to the toast's on-screen corner before drawing, and the rest of the
     * overlay modelview is only the ortho setup's z offset — so its x/y ARE GUI coordinates.
     */
    public static void recordGl(int toastWidth, int toastHeight) {
        MODEL_VIEW.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODEL_VIEW);
        record(MODEL_VIEW.get(12), MODEL_VIEW.get(13), toastWidth, toastHeight);
    }

    /** Drops the rectangle — called when the toast hides and on disconnect. */
    public static void clear() {
        recorded = false;
        // swallowRelease stays: the toast can hide while the consumed press is still held, and its
        // release must still be dropped. The next left press resets it anyway.
    }

    /** True inside the recorded rectangle, and only while it is still fresh. */
    public static boolean isOver(double mouseX, double mouseY) {
        if (!recorded || System.nanoTime() - recordedAt > STALE_NANOS) {
            return false;
        }
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * A screen click asks this; true means the click cancelled the job and must not reach the
     * screen. Left button only, and only when the keybind's own gate passes.
     */
    public static boolean onScreenClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        swallowRelease = false; // every left press re-decides, so a lost release can't eat a later one
        if (!isOver(mouseX, mouseY) || !CancelCraftKey.canCancel()) {
            return false;
        }
        CancelCraftKey.sendCancel();
        swallowRelease = true;
        return true;
    }

    /** A screen release asks this; true means it belongs to a press the toast consumed and must be dropped. */
    public static boolean onScreenRelease(int button) {
        if (button != 0 || !swallowRelease) {
            return false;
        }
        swallowRelease = false;
        return true;
    }

    /**
     * Whether the toast should draw its "Click to cancel" hint: a screen is open (cursor free),
     * a click would actually send the cancel, and the mouse sits inside the toast.
     */
    public static boolean mouseOverHint() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null || !CancelCraftKey.canCancel()) {
            return false;
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        return isOver(Mouse.getX() * resolution.getScaledWidth() / mc.displayWidth,
                resolution.getScaledHeight() - Mouse.getY() * resolution.getScaledHeight() / mc.displayHeight - 1);
    }

    /**
     * The 1.12.2 click hook: {@code MouseInputEvent.Pre} fires per queued LWJGL mouse event but
     * carries no coordinates, so the handler reads them off the event queue and converts them
     * exactly like {@code GuiScreen.handleMouseInput} does. Registered from {@code ClientProxy}.
     */
    public static final class Handler {

        @SubscribeEvent
        public void onMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
            if (Mouse.getEventButton() != 0) {
                return; // only the left button reaches the toast (-1 = a plain move)
            }
            if (!Mouse.getEventButtonState()) {
                if (ToastCancelClick.onScreenRelease(0)) {
                    event.setCanceled(true); // the release of a press the toast took
                }
                return;
            }
            GuiScreen gui = event.getGui();
            Minecraft mc = Minecraft.getMinecraft();
            int x = Mouse.getEventX() * gui.width / mc.displayWidth;
            int y = gui.height - Mouse.getEventY() * gui.height / mc.displayHeight - 1;
            if (ToastCancelClick.onScreenClick(x, y, 0)) {
                event.setCanceled(true); // the press was the toast's; the screen never sees it
            }
        }
    }
}
