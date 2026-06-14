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

    public static void accept(int token, ResourceLocation targetItem, int targetCount,
                              boolean approximate, java.util.List<com.legoj15.sprawlcrafting.craft.ItemDemand> demands) {
        latestToken = token;
        latest = new ShortfallView(targetItem, targetCount, approximate, demands);
    }

    public static java.util.Optional<ShortfallView> ifReady(int token) {
        return latest != null && latestToken == token
                ? java.util.Optional.of(latest) : java.util.Optional.empty();
    }

    public static void reset() {
        latestToken = 0;
        latest = null;
        pendingOpen = null;
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
        return CraftPlanner.session(mc.level.getRecipeManager(), mc.level.registryAccess(),
                mc.player.getInventory(), grid).shortfall(holder);
    }

    public static void reset() {
        // 1.21.1 recomputes on demand; nothing cached to clear (but drop any queued open).
        pendingOpen = null;
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
