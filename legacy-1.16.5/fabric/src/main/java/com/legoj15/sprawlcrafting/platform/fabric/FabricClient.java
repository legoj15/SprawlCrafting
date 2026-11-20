package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.client.CancelCraftKey;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.MixinSelfCheck;
import com.legoj15.sprawlcrafting.client.ToastCancelClick;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.network.CraftProgressPacket;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

public class FabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SprawlConfig.get(); // load (and create on first run) the config at client start
        // Decoded on the netty thread, applied on the render thread.
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressPacket.ID,
                (client, handler, buf, sender) -> {
                    final CraftProgressPacket packet = CraftProgressPacket.read(buf);
                    client.execute(() -> ClientJobTracker.accept(packet));
                });
        // Tell the server which crafting grid is on screen (only while a job is active), so a
        // deferred craft's final step can be handed off to it for the player to grab.
        ClientTickEvents.END_CLIENT_TICK.register(client -> CraftingScreenWatcher.clientTick());
        // The Cancel deferred craft keybind (unbound by default) and its per-tick poll.
        KeyBindingHelper.registerKeyBinding(CancelCraftKey.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CancelCraftKey.clientTick());
        // Clicking the progress toast while any screen is open cancels the craft (issue #34):
        // returning false from allowMouseClick vetoes the screen's own handling of the press.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenMouseEvents.allowMouseClick(screen).register((mouseScreen, mouseX, mouseY, button) ->
                        !ToastCancelClick.onScreenClick(mouseX, mouseY, button)));
        // Drop all per-session client state on disconnect so nothing leaks into the next world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            CraftingScreenWatcher.resetAllClientState();
            ToastCancelClick.clear(); // the toast's clickable rectangle dies with the job state
        }));
        MixinSelfCheck.runIfRequested(); // dev-only, SC_MIXIN_SELFTEST=1
    }
}
