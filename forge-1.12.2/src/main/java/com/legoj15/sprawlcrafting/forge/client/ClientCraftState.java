package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.network.CraftProgressCountsMessage;
import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;

/**
 * Client-side mirror of the active job's progress, fed by {@link CraftProgressMessage} — or, on a
 * server that counts this client capable, its long twin {@link CraftProgressCountsMessage}
 * (issue #46) — and read live by {@link CraftProgressToast}. Counts are longs because that second
 * form carries a job bigger than {@link Integer#MAX_VALUE} crafts without scaling. A tiny
 * volatile-field holder rather than a full model — there is only ever one job and one consumer
 * (the render thread). {@link #update} also owns the job's presentation side effects: it keeps
 * the singleton toast alive and plays the per-step chime (modern parity: UI_TOAST_IN at double
 * pitch whenever the completed-craft count advances, {@code soundEffects}-gated).
 */
public final class ClientCraftState {

    private ClientCraftState() {
    }

    /** How long a terminal (finished/cancelled) flyout lingers before it hides itself. */
    public static final long TERMINAL_LINGER_MS = 3000L;

    public static volatile boolean active = false;
    public static volatile ItemStack target = ItemStack.EMPTY;
    public static volatile ItemStack current = ItemStack.EMPTY;
    public static volatile long done = 0L;
    public static volatile long total = 0L;
    public static volatile CraftProgressMessage.State state = CraftProgressMessage.State.CRAFTING;
    /** Wall-clock millis a terminal state arrived, for fade-out; 0 while a job is live. */
    public static volatile long terminalSinceMs = 0L;

    /**
     * The one sink both progress forms feed (the int message widens its counts into these
     * parameters), so the HUD behaves identically whichever the server picked for this client.
     */
    public static void update(CraftProgressMessage.State newState, ItemStack newTarget,
            ItemStack newCurrent, long newDone, long newTotal) {
        boolean stepAdvanced = active && newDone > done;
        target = newTarget;
        current = newCurrent;
        done = newDone;
        total = newTotal;
        state = newState;
        active = true;
        terminalSinceMs = isTerminal(newState) ? System.currentTimeMillis() : 0L;

        // Presentation (runs on the client thread — the packet handler schedules update() there).
        Minecraft mc = Minecraft.getMinecraft();
        GuiToast toasts = mc.getToastGui();
        if (toasts.getToast(CraftProgressToast.class, CraftProgressToast.NO_TOKEN) == null) {
            toasts.add(new CraftProgressToast());
        }
        if (stepAdvanced && SprawlConfig.soundEffects
                && state != CraftProgressMessage.State.CANCELLED) {
            mc.getSoundHandler().playSound(
                    PositionedSoundRecord.getMasterRecord(SoundEvents.UI_TOAST_IN, 2.0F));
        }
    }

    public static void clear() {
        active = false;
        target = ItemStack.EMPTY;
        current = ItemStack.EMPTY;
        done = 0L;
        total = 0L;
        terminalSinceMs = 0L;
    }

    /**
     * Whether a live (non-terminal) job is shown — running or paused. The cancel keybind gates on
     * this: once the flyout shows a terminal state that job is already gone server-side, so a
     * press then must not send a packet. The same notion of "live" {@code CraftingScreenWatcher}
     * reads off {@code terminalSinceMs == 0}.
     */
    public static boolean hasActiveJob() {
        return active && !isTerminal(state);
    }

    /** The states whose sync is the job's last: after them the job no longer exists server-side. */
    private static boolean isTerminal(CraftProgressMessage.State state) {
        return state == CraftProgressMessage.State.FINISHED
                || state == CraftProgressMessage.State.CANCELLED
                || state == CraftProgressMessage.State.READY_IN_GRID;
    }
}
