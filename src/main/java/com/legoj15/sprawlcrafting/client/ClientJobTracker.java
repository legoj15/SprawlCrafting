package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Render-thread-only holder of the latest {@link CraftProgressPayload} snapshot, and the
 * lifecycle owner of the single {@link CraftProgressToast}: a toast is added when a job
 * becomes visible and reused for every update until it hides itself (terminal state
 * displayed for a moment, or state gone).
 */
public final class ClientJobTracker {

    /** How long a finished/cancelled toast lingers before sliding out. */
    public static final long TERMINAL_DISPLAY_MS = 2500;

    /** The per-step craft "pop": vanilla's toast-in sound an octave up (2x pitch). */
    private static final float STEP_SOUND_PITCH = 2.0F;

    private static CraftProgressPayload current;
    private static long terminalAtMillis;

    private ClientJobTracker() {
    }

    //? if >=1.21.11 {
    /*private static net.minecraft.client.gui.components.toasts.ToastManager toasts() {
        return Minecraft.getInstance().getToastManager();
    }*/
    //?} else {
    private static net.minecraft.client.gui.components.toasts.ToastComponent toasts() {
        return Minecraft.getInstance().getToasts();
    }
    //?}

    public static void accept(CraftProgressPayload payload) {
        boolean wasTerminal = current != null && current.state().isTerminal();
        if (payload.state().isTerminal() && !(wasTerminal)) {
            terminalAtMillis = Util.getMillis();
        }
        // A completed-count advance means a craft just executed — play one toast "pop" per
        // step so a multi-step deferred job ticks audibly as it works. done() only advances
        // on an actual craft (CRAFTING between steps, FINISHED/READY_IN_GRID on the last);
        // job start (done=0), PAUSED retries, and CANCELLED leave it unchanged, so they
        // stay silent.
        if (current != null && payload.done() > current.done()) {
            playStepSound();
        }
        current = payload;
        // Presence check rather than a local flag: vanilla clears all toasts on
        // disconnect without our toast ever returning HIDE, so a flag would desync and
        // permanently suppress future toasts. Asking ToastComponent directly cannot.
        if (toasts().getToast(CraftProgressToast.class, Toast.NO_TOKEN) == null) {
            toasts().addToast(new CraftProgressToast());
        }
    }

    /** Clears all job state — call on client disconnect so nothing leaks into the next world. */
    public static void reset() {
        current = null;
        terminalAtMillis = 0;
    }

    /** Latest snapshot, or null when there is nothing to show. */
    public static CraftProgressPayload current() {
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

    /**
     * Plays the toast "pop" (UI_TOAST_IN) at 2x pitch and full toast volume. Vanilla plays
     * the same sound at pitch 1.0 when a toast appears ({@code Toast.Visibility.playSound}
     * uses {@code forUI(sound, 1.0F, 1.0F)}), so the 3-arg overload — volume 1.0, not the
     * 2-arg's 0.25 default — matches that loudness. {@code SoundManager.play} returns void
     * on 1.21.1 and a PlayResult on 26.x; discarding the result compiles on both.
     */
    private static void playStepSound() {
        if (!SprawlConfig.get().soundEffects()) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, STEP_SOUND_PITCH, 1.0F));
    }

    /** Called by the toast as it hides; the next update will spawn a fresh toast. */
    public static void onToastHidden() {
        if (current != null && current.state().isTerminal()) {
            current = null;
        }
    }
}
