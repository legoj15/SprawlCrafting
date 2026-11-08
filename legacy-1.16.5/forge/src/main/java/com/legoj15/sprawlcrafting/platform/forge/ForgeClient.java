package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.MixinSelfCheck;
import com.legoj15.sprawlcrafting.client.SprawlConfigScreen;
import com.legoj15.sprawlcrafting.config.SprawlConfig;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Client-only wiring, reached from {@link ForgeMod} through {@code DistExecutor} so none of these
 * client classes load on a dedicated server: the mod-list "Config" button, the per-tick crafting-screen
 * watcher, and the disconnect reset that keeps per-session state from leaking into the next world.
 */
public final class ForgeClient {

    private ForgeClient() {
    }

    public static void init() {
        SprawlConfig.get(); // load (and create on first run) the config at client start
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
                () -> (minecraft, modListScreen) -> new SprawlConfigScreen(modListScreen));
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onLoggedOut);
        MixinSelfCheck.runIfRequested(); // dev-only, SC_MIXIN_SELFTEST=1
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CraftingScreenWatcher.clientTick();
        }
    }

    private static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        CraftingScreenWatcher.resetAllClientState();
    }
}
