package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.ShortfallView;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;

/**
 * Client-side owner of "gather list" (shortfall) data: the raw materials a player still needs to make a
 * red/unmakeable recipe. 1.16.5 clients have the full RecipeManager and inventory, so the list is solved
 * locally with no round trip.
 */
public final class MissingIngredients {

    private MissingIngredients() {
    }

    /**
     * The raw materials still needed to make {@code recipe}, solved locally. Callers pass
     * {@link GridContext#CRAFTING_TABLE} to cost the full chain since the list is informational.
     */
    public static ShortfallView shortfallLocal(IRecipe<?> recipe, GridContext grid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return ShortfallView.unavailable();
        }
        // With a crafting station open (AE2 Crafting Terminal) its storage counts as already gathered.
        // Protected stacks (enchanted/renamed, #40) count whenever the server can honour a protected
        // start: a confirmed craft consumes them, so the list must not ask for what is held (#48) —
        // including the station's protected stacks when that server also negotiated the
        // station-protected marker (#49); the counts accessor is already gated to exactly that.
        return CraftPlanner.session(mc.level.getRecipeManager(), mc.player.inventory, grid,
                DeferredCraftableCache.stationCounts(),
                DeferredCraftableCache.stationProtectedCounts(),
                Services.PLATFORM.canStartProtectedCraft())
                .shortfall(recipe);
    }

    public static void reset() {
        pendingOpen = null; // nothing cached; just drop any queued open
    }

    public static Item itemOf(ResourceLocation id) {
        return Registry.ITEM.get(id);
    }

    // --- Deferred screen open (JEI path) -------------------------------------------------------
    // A JEI transfer-button click triggers JEI's own GUI teardown, so opening our screen inline gets
    // swept away with it. Queue the open and re-assert it from later client ticks until it sticks.
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
            pendingOpen = null;
            return;
        }
        if (pendingTicks-- <= 0) {
            pendingOpen = null;
            return;
        }
        pendingOpen.run();
        if (Minecraft.getInstance().screen instanceof MissingIngredientsScreen) {
            pendingOpen = null; // took this tick — an ESC next tick must not re-trigger it
        }
    }
}
