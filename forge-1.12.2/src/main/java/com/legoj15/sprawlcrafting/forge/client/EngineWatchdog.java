package com.legoj15.sprawlcrafting.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Tells the player, once, when their deferred-craft clicks are going nowhere. The mod deliberately
 * lets a modded client join a server without SprawlCrafting ({@code acceptableRemoteVersions="*"}),
 * where the yellow recipe-book highlights and JEI buttons still render (they're client-solved) but
 * an engine start sent to the server is silently dropped as an unknown channel — a dead click that
 * looks like a broken feature. This can't be gated on {@link ServerPresence}: that flag means
 * "speaks the v2 channel", and a v1-modded server runs the engine fine without ever saying the v2
 * hello. So instead: arm a short countdown when an engine request leaves for a server that hasn't
 * identified itself; any {@code CraftProgressMessage} ever arriving proves the engine exists and
 * permanently disarms it; if the countdown expires with total silence, print one localized notice
 * for the connection. Known accepted edge: a v1 server REJECTING the very first click replies only
 * via plain chat the client can't attribute, so that narrow case can show the notice alongside the
 * server's own error, once.
 */
public final class EngineWatchdog {

    private static final int TIMEOUT_TICKS = 40;

    private static volatile int pendingTicks;
    private static volatile boolean sawEngineTraffic;
    private static volatile boolean noticeShown;

    private EngineWatchdog() {
    }

    /** Called by the network helpers whenever an engine-start message goes out (client thread). */
    public static void engineRequestSent() {
        if (!ServerPresence.active() && !sawEngineTraffic && !noticeShown) {
            pendingTicks = TIMEOUT_TICKS;
        }
    }

    /** Any progress packet proves the server runs the engine — disarm for the whole connection. */
    public static void engineResponded() {
        sawEngineTraffic = true;
        pendingTicks = 0;
    }

    /** Fresh start per connection (called from the disconnect cleanup). */
    public static void reset() {
        pendingTicks = 0;
        sawEngineTraffic = false;
        noticeShown = false;
    }

    /** Registered on the event bus by the client proxy. */
    public static final class Ticker {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || pendingTicks <= 0) {
                return;
            }
            if (--pendingTicks > 0) {
                return;
            }
            if (sawEngineTraffic || ServerPresence.active() || noticeShown) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player != null) {
                noticeShown = true;
                TextComponentTranslation notice =
                        new TextComponentTranslation("sprawlcrafting.server_missing");
                notice.getStyle().setColor(TextFormatting.RED);
                mc.player.sendMessage(notice);
            }
        }
    }
}
