package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.client.CancelCraftKey;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.SprawlConfigScreen;
import com.legoj15.sprawlcrafting.client.ToastCancelClick;
import com.legoj15.sprawlcrafting.config.SprawlConfig;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
//? if <1.21.11 {
import net.minecraftforge.common.MinecraftForge;
//?}
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//? if <1.20.5 {
/*import net.minecraftforge.fml.ModLoadingContext;*/
//?}

/**
 * Client-only setup, called from {@link ForgeMod} behind a dist guard so its references to client
 * classes never load on a dedicated server. Handles client disconnect to drop all per-session
 * deferred-craft client state, preventing a stale progress toast, a synced deferred-craftable set, or
 * a pending preview from leaking into the next world.
 */
final class ForgeClient {

    private ForgeClient() {
    }

    static void init(FMLJavaModLoadingContext context) {
        SprawlConfig.get(); // load (and create on first run) the config at client start
        // The mod-list "Config" button: the factory hands back our screen with the list as its parent.
        //? if >=1.20.5 {
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, modListScreen) -> new SprawlConfigScreen(modListScreen)));
        //?} else {
        /*// Forge 47's FMLJavaModLoadingContext has no registerExtensionPoint; the generic context does.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, modListScreen) -> new SprawlConfigScreen(modListScreen)));*/
        //?}
        // Forge 26.x (EventBus 7) deprecates MinecraftForge.EVENT_BUS (slated for removal in 26.4):
        // every event class carries its own static BUS instead.
        //? if >=1.21.11 {
        /*ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(ForgeClient::onLoggingOut);
        TickEvent.ClientTickEvent.Post.BUS.addListener(ForgeClient::onClientTick);*/
        //?} else {
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(ForgeClient::onClientTick);
        //?}
        // Clicking the progress toast while any screen is open cancels the craft (issue #34):
        // canceling the Pre event keeps the press from the screen underneath. On 26.x (EventBus 7)
        // the listener is a predicate whose true return cancels.
        //? if >=1.21.11 {
        /*ScreenEvent.MouseButtonPressed.Pre.BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) ->
                ToastCancelClick.onScreenClick(e.getMouseX(), e.getMouseY(), e.getButton()));*/
        //?} else {
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (ToastCancelClick.onScreenClick(e.getMouseX(), e.getMouseY(), e.getButton())) {
                e.setCanceled(true);
            }
        });
        //?}
        // The cancel key, registered through the loader's key-registration event (never added to
        // Options by hand). Forge 26 (EventBus 7) made it a game-bus event on its own static BUS;
        // before that it is a mod-bus event.
        //? if >=1.21.11 {
        /*RegisterKeyMappingsEvent.BUS.addListener((RegisterKeyMappingsEvent e) -> e.register(CancelCraftKey.KEY));*/
        //?} else {
        context.getModEventBus().addListener((RegisterKeyMappingsEvent e) -> e.register(CancelCraftKey.KEY));
        //?}
        ForgeMixinSelfCheck.runIfRequested(); // dev/agent only: SC_MIXIN_SELFTEST=1
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientJobTracker.reset();
        CraftingScreenWatcher.reset();       // drop the last-sent open-grid state
        DeferredCraftableCache.invalidate(); // drop the synced/solved craftable set
        DeferredClickState.clear();          // drop any pending click preview
        MissingIngredients.reset();          // drop any pending/cached gather list
        ToastCancelClick.clear();            // the toast's clickable rectangle dies with the job state
    }

    // Tell the server which crafting grid is on screen (only while a job is active), so a
    // deferred craft's final step can be handed off to it for the player to grab.
    //? if >=1.20.5 {
    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        CraftingScreenWatcher.clientTick();
        CancelCraftKey.clientTick();
    }
    //?} else {
    /*// Forge 47 has no Pre/Post subclasses: one event per phase, END = post.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            CraftingScreenWatcher.clientTick();
            CancelCraftKey.clientTick();
        }
    }*/
    //?}
}
