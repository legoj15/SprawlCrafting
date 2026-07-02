package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * One-shot deferral for opening the missing-resources screen from a JEI "+" click. Opening it
 * synchronously inside the click would lose: JEI treats a null transfer result as success and
 * closes its recipes GUI back to the parent container afterwards, replacing whatever screen the
 * handler had just displayed (the modern port hit the identical ordering trap and queues the open
 * there too). Instead the handler parks the shortfall here and the next client tick — after JEI
 * has finished restoring the container screen — opens the gather screen over it, so Done/Escape
 * lands back on the inventory or table, exactly like the recipe-book path.
 */
public final class PendingGatherScreen {

    private static ShortfallView pending;

    private PendingGatherScreen() {
    }

    /** Client thread only (JEI transfer handlers run there). */
    public static void open(ShortfallView shortfall) {
        pending = shortfall;
    }

    /** Registered on the event bus by the client proxy. */
    public static final class Ticker {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || pending == null) {
                return;
            }
            ShortfallView shortfall = pending;
            pending = null;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player != null) {
                mc.displayGuiScreen(new GuiMissingResources(shortfall, mc.currentScreen));
            }
        }
    }
}
