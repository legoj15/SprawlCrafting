package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;

/**
 * Client-side mirror of the active job's progress, fed by {@link CraftProgressMessage} and read
 * live by {@link CraftProgressToast}. A tiny volatile-field holder rather than a full model —
 * there is only ever one job and one consumer (the render thread). {@link #update} also owns the
 * job's presentation side effects: it keeps the singleton toast alive and plays the per-step chime
 * (modern parity: UI_TOAST_IN at double pitch whenever the completed-craft count advances,
 * {@code soundEffects}-gated).
 */
public final class ClientCraftState {

    private ClientCraftState() {
    }

    /** How long a terminal (finished/cancelled) flyout lingers before it hides itself. */
    public static final long TERMINAL_LINGER_MS = 3000L;

    public static volatile boolean active = false;
    public static volatile ItemStack target = ItemStack.EMPTY;
    public static volatile ItemStack current = ItemStack.EMPTY;
    public static volatile int done = 0;
    public static volatile int total = 0;
    public static volatile CraftProgressMessage.State state = CraftProgressMessage.State.CRAFTING;
    /** Wall-clock millis a terminal state arrived, for fade-out; 0 while a job is live. */
    public static volatile long terminalSinceMs = 0L;

    public static void update(CraftProgressMessage message) {
        boolean stepAdvanced = active && message.done() > done;
        target = message.target();
        current = message.current();
        done = message.done();
        total = message.total();
        state = message.state();
        active = true;
        boolean terminal = state == CraftProgressMessage.State.FINISHED
                || state == CraftProgressMessage.State.CANCELLED
                || state == CraftProgressMessage.State.READY_IN_GRID;
        terminalSinceMs = terminal ? System.currentTimeMillis() : 0L;

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
        done = 0;
        total = 0;
        terminalSinceMs = 0L;
    }
}
