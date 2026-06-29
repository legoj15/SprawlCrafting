package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.network.CraftProgressMessage;

import net.minecraft.item.ItemStack;

/**
 * Client-side mirror of the active job's progress, fed by {@link CraftProgressMessage} and read by
 * the HUD overlay. A tiny volatile-field holder rather than a full model — there is only ever one
 * job and one consumer (the render thread). The modern tree's richer client tracker has no analog
 * here because this port has no recipe-book client UI to feed.
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
        target = message.target();
        current = message.current();
        done = message.done();
        total = message.total();
        state = message.state();
        active = true;
        boolean terminal = state == CraftProgressMessage.State.FINISHED
                || state == CraftProgressMessage.State.CANCELLED;
        terminalSinceMs = terminal ? System.currentTimeMillis() : 0L;
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
