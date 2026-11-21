package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.ShortfallView;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Client-side owner of "gather list" (shortfall) data: the raw materials a player still needs to make
 * a red/unmakeable recipe.
 *
 * <p>On 1.21.1 the client has the full RecipeManager and inventory, so it solves the list locally with
 * no round trip ({@link #shortfallLocal}). On 26.x the client cannot read recipes, so a token'd request
 * goes to the server and the reply lands in {@link #accept}; the open screen polls {@link #ifReady} for
 * its own token (so a stale answer to a previous query is ignored).
 */
public final class MissingIngredients {

    private MissingIngredients() {
    }

    //? if >=1.21.11 {
    /*// 26.x: the gather list is server-computed. Fire a token'd request, stash the latest reply, and
    // let the open screen pick up only the answer that matches its token.
    private static int nextToken = 1;
    private static int latestToken = 0;
    private static ShortfallView latest = null;

    public static int requestByDisplay(net.minecraft.world.item.crafting.display.RecipeDisplayId id) {
        int token = nextToken++;
        com.legoj15.sprawlcrafting.platform.Services.PLATFORM.sendToServer(
                new com.legoj15.sprawlcrafting.network.RequestShortfallByDisplayPayload(token, id.index()));
        return token;
    }

    public static int requestByRecipe(ResourceLocation recipeId) {
        int token = nextToken++;
        com.legoj15.sprawlcrafting.platform.Services.PLATFORM.sendToServer(
                new com.legoj15.sprawlcrafting.network.RequestShortfallByRecipePayload(token, recipeId));
        return token;
    }

    // Fed by both the V2 payload and the legacy mod-1.0.3 one (whose counts are clamped ints, so
    // saturated is always false there).
    public static void accept(int token, ResourceLocation targetItem, int targetCount, boolean approximate,
                              boolean saturated, java.util.List<com.legoj15.sprawlcrafting.craft.ItemDemand> demands) {
        latestToken = token;
        latest = new ShortfallView(targetItem, targetCount, approximate, saturated, demands);
    }

    public static java.util.Optional<ShortfallView> ifReady(int token) {
        return latest != null && latestToken == token
                ? java.util.Optional.of(latest) : java.util.Optional.empty();
    }

    public static void reset() {
        latestToken = 0;
        latest = null;
        pendingOpen = null;
    }

    // A server without SprawlCrafting never answers a request (Forge drops it, the list loads forever)
    // or refuses to send it at all (NeoForge throws from the click handler, crashing the client), so
    // every trigger offers the gather list only when the server registered the request it will send (#42).
    public static boolean canRequestByDisplay() {
        return serverHas(com.legoj15.sprawlcrafting.network.RequestShortfallByDisplayPayload.ID);
    }

    public static boolean canRequestByRecipe() {
        return serverHas(com.legoj15.sprawlcrafting.network.RequestShortfallByRecipePayload.ID);
    }

    private static boolean serverHas(ResourceLocation payloadId) {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && com.legoj15.sprawlcrafting.platform.Services.PLATFORM.canSendToServer(
                connection.getConnection(), payloadId);
    }*/
    //?} else {
    /**
     * The raw materials still needed to make {@code holder}, solved locally. Costs the full chain via
     * {@link GridContext#CRAFTING_TABLE} since the gather list is informational, not a craft start.
     */
    public static ShortfallView shortfallLocal(RecipeHolder<?> holder, GridContext grid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return ShortfallView.unavailable();
        }
        // With a crafting station open (AE2 Crafting Terminal) its storage counts as already gathered,
        // protected stacks included (the player's, #40, and the station's own, #49) whenever the
        // server can honour a protected start: a confirmed craft consumes them, so the list must not
        // ask for what is held (#48).
        boolean includeProtected = mc.getConnection() != null
                && com.legoj15.sprawlcrafting.platform.Services.PLATFORM.canSendToServer(
                        mc.getConnection().getConnection(),
                        com.legoj15.sprawlcrafting.network.StartProtectedCraftPayload.ID);
        return CraftPlanner.session(mc.level.getRecipeManager(), mc.level.registryAccess(),
                mc.player.getInventory(), grid, DeferredCraftableCache.stationCounts(),
                DeferredCraftableCache.stationProtectedCounts(), includeProtected)
                .shortfall(holder);
    }

    public static void reset() {
        // 1.21.1 recomputes on demand; nothing cached to clear (but drop any queued open).
        pendingOpen = null;
    }

    /** 1.21.1 solves the list on the client: always available, whatever the server runs. */
    public static boolean canRequestByDisplay() {
        return true;
    }

    public static boolean canRequestByRecipe() {
        return true;
    }
    //?}

    /** Resolves a demand's item id to its {@link Item}, bridging the 1.21.x registry-API rename. */
    public static Item itemOf(ResourceLocation id) {
        //? if >=1.21.11 {
        /*return BuiltInRegistries.ITEM.getValue(id);*/
        //?} else {
        return BuiltInRegistries.ITEM.get(id);
        //?}
    }

    // --- Deferred screen open (JEI path) -------------------------------------------------------
    // The JEI transfer-button click triggers JEI's own GUI teardown, so opening our screen inline or
    // via Minecraft.execute gets swept away with it. Queue the open and re-assert it from later client
    // ticks — outside JEI's click flow — until our screen is the active one or the window lapses.
    private static Runnable pendingOpen;
    private static int pendingTicks;

    public static void requestOpenNextTick(Runnable open) {
        pendingOpen = open;
        pendingTicks = 10;
    }

    /** Called every client tick (ungated). Drives a queued open until it sticks or times out. */
    public static void pollPendingOpen() {
        if (pendingOpen == null) {
            return;
        }
        if (Minecraft.getInstance().screen instanceof MissingIngredientsScreen) {
            pendingOpen = null; // our screen is showing — done
            return;
        }
        if (pendingTicks-- <= 0) {
            pendingOpen = null; // stop re-asserting
            return;
        }
        pendingOpen.run();
        if (Minecraft.getInstance().screen instanceof MissingIngredientsScreen) {
            // It took this tick — stop now so an ESC on the very next tick can't re-trigger the open.
            pendingOpen = null;
        }
    }
}
