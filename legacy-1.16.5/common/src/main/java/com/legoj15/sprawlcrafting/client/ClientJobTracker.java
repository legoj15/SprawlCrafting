package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.network.CraftProgressPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.gui.toasts.ToastGui;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.Util;

/**
 * Render-thread-only holder of the latest {@link CraftProgressPacket} snapshot, and the lifecycle owner
 * of the single {@link CraftProgressToast}: a toast is added when a job becomes visible and reused for
 * every update until it hides itself (terminal state displayed for a moment, or state gone).
 */
public final class ClientJobTracker {

    /** How long a finished/cancelled toast lingers before sliding out. */
    public static final long TERMINAL_DISPLAY_MS = 2500;

    /** The per-step craft "pop": vanilla's toast-in sound an octave up (2x pitch). */
    private static final float STEP_SOUND_PITCH = 2.0F;

    private static CraftProgressPacket current;
    private static long terminalAtMillis;

    private ClientJobTracker() {
    }

    private static ToastGui toasts() {
        return Minecraft.getInstance().getToasts();
    }

    public static void accept(CraftProgressPacket packet) {
        boolean wasTerminal = current != null && current.state().isTerminal();
        if (packet.state().isTerminal() && !wasTerminal) {
            terminalAtMillis = Util.getMillis();
        }
        // A completed-count advance means a craft just executed — one "pop" per step. Job start,
        // PAUSED retries and CANCELLED leave done() unchanged, so they stay silent.
        if (current != null && packet.done() > current.done()) {
            playStepSound();
        }
        current = packet;
        // Presence check rather than a local flag: vanilla clears all toasts on disconnect without our
        // toast ever returning HIDE, so a flag would desync and suppress future toasts.
        if (toasts().getToast(CraftProgressToast.class, IToast.NO_TOKEN) == null) {
            toasts().addToast(new CraftProgressToast());
        }
    }

    /** Clears all job state — call on client disconnect so nothing leaks into the next world. */
    public static void reset() {
        current = null;
        terminalAtMillis = 0;
    }

    /** Latest snapshot, or null when there is nothing to show. */
    public static CraftProgressPacket current() {
        return current;
    }

    /** True while a job is running or paused — used to gate new craft offers in UIs. */
    public static boolean hasActiveJob() {
        return current != null && !current.state().isTerminal();
    }

    /** True once a terminal snapshot has been on screen long enough to slide out. */
    public static boolean terminalDisplayElapsed() {
        return current != null && current.state().isTerminal()
                && Util.getMillis() - terminalAtMillis > TERMINAL_DISPLAY_MS;
    }

    /** The toast "pop" (UI_TOAST_IN) at 2x pitch and full toast volume, like vanilla's toast-in. */
    private static void playStepSound() {
        if (!SprawlConfig.get().soundEffects()) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(
                SimpleSound.forUI(SoundEvents.UI_TOAST_IN, STEP_SOUND_PITCH, 1.0F));
    }

    /** Called by the toast as it hides; the next update will spawn a fresh toast. */
    public static void onToastHidden() {
        if (current != null && current.state().isTerminal()) {
            current = null;
        }
    }
}
