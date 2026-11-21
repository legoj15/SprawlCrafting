package com.legoj15.sprawlcrafting.platform.fabric;

import com.legoj15.sprawlcrafting.client.CancelCraftKey;
import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.CraftingScreenWatcher;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.ToastCancelClick;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.network.CraftPreviewPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressCountsPayload;
import com.legoj15.sprawlcrafting.network.CraftProgressPayload;
import com.legoj15.sprawlcrafting.network.DeferredCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.ProtectedCraftableSyncPayload;
import com.legoj15.sprawlcrafting.network.ShortfallPayload;
import com.legoj15.sprawlcrafting.network.ShortfallV2Payload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

public class FabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        SprawlConfig.get(); // load (and create on first run) the config at client start
        //? if >=1.20.5 {
        // Handler runs on the render thread (Fabric dispatches before invoking).
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressPayload.TYPE,
                (payload, context) -> ClientJobTracker.accept(payload));
        // Capability marker only (never sent): registering it tells the server this client decodes
        // PAUSED_STATION (see ProgressStatesV2Payload).
        ClientPlayNetworking.registerGlobalReceiver(com.legoj15.sprawlcrafting.network.ProgressStatesV2Payload.TYPE,
                (payload, context) -> { });
        // The long-count snapshot (#46): registering it is what tells the server this client takes
        // real longs; the payload converts to the tracker's one type on receipt.
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressCountsPayload.TYPE,
                (payload, context) -> ClientJobTracker.accept(payload.toProgressPayload()));
        //?} else {
        /*// Pre-1.20.5: channel id + raw buffer, delivered on the netty thread — decode there, then
        // hand the snapshot to the render thread.
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressPayload.ID,
                (client, handler, buf, responseSender) -> {
                    CraftProgressPayload payload = CraftProgressPayload.read(buf);
                    client.execute(() -> ClientJobTracker.accept(payload));
                });
        // The long-count snapshot (#46) on its own raw channel, registered so the server's
        // canReceive answers true for its id (a 1.0.x client never declares it and keeps the
        // scaled-int legacy payload).
        ClientPlayNetworking.registerGlobalReceiver(CraftProgressCountsPayload.ID,
                (client, handler, buf, responseSender) -> {
                    CraftProgressCountsPayload payload = CraftProgressCountsPayload.read(buf);
                    client.execute(() -> ClientJobTracker.accept(payload.toProgressPayload()));
                });*/
        //?}
        // 26.x: receive the server-computed deferred-craftable set + plan previews. 1.21.1 computes
        // these on the client, so it registers no receivers here.
        //? if >=1.21.11 {
        /*ClientPlayNetworking.registerGlobalReceiver(DeferredCraftableSyncPayload.TYPE,
                (payload, context) -> DeferredCraftableCache.accept(payload.displayIds(), payload.recipeIds()));
        // The protected-only subset of that set (#40): members confirm with the protected start.
        ClientPlayNetworking.registerGlobalReceiver(ProtectedCraftableSyncPayload.TYPE,
                (payload, context) -> DeferredCraftableCache.acceptProtected(
                        payload.displayIds(), payload.recipeIds()));
        ClientPlayNetworking.registerGlobalReceiver(CraftPreviewPayload.TYPE,
                (payload, context) -> DeferredClickState.acceptPreview(payload.displayId(), payload.lines()));
        ClientPlayNetworking.registerGlobalReceiver(ShortfallPayload.TYPE,
                (payload, context) -> MissingIngredients.accept(
                        payload.token(), payload.targetItem(), payload.targetCount(),
                        payload.approximate(), false, payload.demands()));
        // V2 (long counts + saturated); the legacy receiver above stays for mod-1.0.3 servers.
        ClientPlayNetworking.registerGlobalReceiver(ShortfallV2Payload.TYPE,
                (payload, context) -> MissingIngredients.accept(
                        payload.token(), payload.targetItem(), payload.targetCount(),
                        payload.approximate(), payload.saturated(), payload.demands()));*/
        //?}
        // The cancel key, registered through the loader's key-registration hook (never added to
        // Options by hand). Fabric renamed the helper along with 26.x's KeyMapping rename.
        //? if >=1.21.11 {
        /*net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(CancelCraftKey.KEY);*/
        //?} else {
        net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(CancelCraftKey.KEY);
        //?}
        // Clicking the progress toast while any screen is open cancels the craft (issue #34):
        // returning false from allowMouseClick vetoes the screen's own handling of the press, and
        // allowMouseRelease drops that press's release (a lone release can drop a carried stack).
        // 26.x's fabric screen api hands the handler a MouseButtonEvent instead of raw coords.
        //? if >=1.21.11 {
        /*ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenMouseEvents.allowMouseClick(screen).register((mouseScreen, event) ->
                    !ToastCancelClick.onScreenClick(event.x(), event.y(), event.button()));
            ScreenMouseEvents.allowMouseRelease(screen).register((mouseScreen, event) ->
                    !ToastCancelClick.onScreenRelease(event.button()));
        });*/
        //?} else {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenMouseEvents.allowMouseClick(screen).register((mouseScreen, mouseX, mouseY, button) ->
                    !ToastCancelClick.onScreenClick(mouseX, mouseY, button));
            ScreenMouseEvents.allowMouseRelease(screen).register((mouseScreen, mouseX, mouseY, button) ->
                    !ToastCancelClick.onScreenRelease(button));
        });
        //?}
        // Tell the server which crafting grid is on screen (only while a job is active), so a
        // deferred craft's final step can be handed off to it for the player to grab.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CraftingScreenWatcher.clientTick();
            CancelCraftKey.clientTick();
        });
        // Drop all per-session client state on disconnect so a stale toast, a synced
        // deferred-craftable set, or a pending preview can't leak into the next world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientJobTracker.reset();
            CraftingScreenWatcher.reset();
            DeferredCraftableCache.invalidate();
            DeferredClickState.clear();
            MissingIngredients.reset();
            ToastCancelClick.clear(); // the toast's clickable rectangle dies with the job state
        });
    }
}
