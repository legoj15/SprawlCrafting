package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.client.CancelCraftKey;
import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.MixinSelfCheck;
import com.legoj15.sprawlcrafting.client.SprawlConfigScreen;
import com.legoj15.sprawlcrafting.client.ToastCancelClick;
import com.legoj15.sprawlcrafting.config.SprawlConfig;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only wiring, reached from {@link ForgeMod} through {@code DistExecutor} so none of these
 * client classes load on a dedicated server: the mod-list "Config" button, the per-tick crafting-screen
 * watcher, the Cancel deferred craft keybind, the progress-toast click that cancels the craft while a
 * screen is open (issue #34), and the disconnect reset that keeps per-session state from leaking into
 * the next world.
 */
public final class ForgeClient {

    private ForgeClient() {
    }

    public static void init() {
        SprawlConfig.get(); // load (and create on first run) the config at client start
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
                () -> (minecraft, modListScreen) -> new SprawlConfigScreen(modListScreen));
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onScreenClick);
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onLoggedOut);
        // Keybinds append to Options' key array; client setup is the window where those exist for
        // sure — init() here runs during mod construction, before that.
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ForgeClient::onClientSetup);
        MixinSelfCheck.runIfRequested(); // dev-only, SC_MIXIN_SELFTEST=1
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        ClientRegistry.registerKeyBinding(CancelCraftKey.KEY);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CraftingScreenWatcher.clientTick();
            CancelCraftKey.clientTick();
        }
    }

    // Clicking the progress toast while any screen is open cancels the craft (issue #34): the
    // canceled Pre event means the press never reaches the screen underneath.
    private static void onScreenClick(GuiScreenEvent.MouseClickedEvent.Pre event) {
        if (ToastCancelClick.onScreenClick(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        CraftingScreenWatcher.resetAllClientState();
        ToastCancelClick.clear(); // the toast's clickable rectangle dies with the job state
    }
}
