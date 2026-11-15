package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

/**
 * Registry of crafting-station providers: menus that are a 3×3 crafting grid AND show an item
 * storage whose contents count as craft material while the menu is open (issue #2, the AE2
 * Crafting Terminal). Shared code asks this class; it never names a compat class, so it compiles
 * and runs unchanged on every node, with or without the mod behind a provider.
 *
 * <p>Providers are registered at mod init by {@link #bootstrap()}, only when the owning mod is
 * loaded, and reached by reflection: the compat package is excluded from compilation on nodes
 * that do not pin that mod (see {@code ae2_version} in the build scripts), so a direct reference
 * would not even compile there. With no provider registered every query is a constant-time no-op.
 */
public final class StationStorages {

    /** One station type, implemented in compat code. */
    public interface Provider {

        /**
         * Whether {@code menu} is one of this provider's station menus (by class), on either side. This
         * marks stations whose grid is not a {@link CraftingGrids} grid: they need the final-grid
         * hand-off skipped ({@code CraftQueueManager.canHandOff}) and their own recipe-viewer transfer
         * handlers ({@link #menus}). A provider whose grid is a CraftingGrids grid — the Sophisticated
         * crafting upgrade, laid out by the shared hand-off and already fronted by its own JEI
         * handler — returns false.
         */
        boolean isStationMenu(AbstractContainerMenu menu);

        /**
         * Server: the storage behind the station {@code player} has open, or null when none is open
         * or it is unusable right now (e.g. an ME terminal whose network is offline or out of power).
         * "Usable" is judged live, every call: an unusable station is treated exactly like a closed one
         * (no counts, no draws, no grid), see {@link StationStorages#hasUsableStation}.
         */
        StationStorage open(ServerPlayer player);

        /**
         * Client: plain-item counts of the storage {@code menu} shows (the client's mirror of
         * {@link StationStorage#counts()}), or null when {@code menu} is not a usable station. Called
         * at most once per client tick (the caller caches).
         */
        Map<Item, Long> clientCounts(AbstractContainerMenu menu);

        /** Every station menu class and type, for recipe-viewer transfer-handler registration. */
        List<StationMenu> menus();
    }

    /** A station menu class and its menu type, as recipe viewers key transfer handlers. */
    public record StationMenu(Class<? extends AbstractContainerMenu> menuClass, MenuType<?> menuType) {
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private StationStorages() {
    }

    /**
     * Registers every station provider whose mod is loaded. Called once from {@code CommonClass.init}.
     * A provider class missing from this jar (its compat package was not compiled for this node) is
     * skipped silently; any other failure is logged and the mod carries on without that station.
     */
    public static void bootstrap() {
        tryRegister("ae2", "com.legoj15.sprawlcrafting.compat.ae2.Ae2Station");
        tryRegister("sophisticatedcore", "com.legoj15.sprawlcrafting.compat.sophisticated.SophisticatedStation");
    }

    private static void tryRegister(String modId, String providerClass) {
        if (!Services.PLATFORM.isModLoaded(modId)) {
            return;
        }
        try {
            Class.forName(providerClass).getMethod("init").invoke(null);
            Constants.LOG.info("SprawlCrafting: {} crafting stations enabled", modId);
        } catch (ClassNotFoundException e) {
            // This node was built without that compat (no <mod>_version pinned): nothing to enable.
        } catch (ReflectiveOperationException | LinkageError e) {
            Constants.LOG.warn("SprawlCrafting: could not enable {} crafting stations", modId, e);
        }
    }

    /** Called by a provider's {@code init()}. */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    public static boolean isStationMenu(AbstractContainerMenu menu) {
        for (Provider provider : PROVIDERS) {
            if (provider.isStationMenu(menu)) {
                return true;
            }
        }
        return false;
    }

    /** The storage behind the station the player has open right now, or null. */
    public static StationStorage open(ServerPlayer player) {
        for (Provider provider : PROVIDERS) {
            StationStorage storage = provider.open(player);
            if (storage != null) {
                return storage;
            }
        }
        return null;
    }

    /**
     * Whether the player has a station open that is usable right now. This, not {@link #isStationMenu},
     * decides whether the open station counts as a 3×3 grid (the reach gate in {@code CraftQueueManager}
     * and {@code GridContext.current}): an open but offline terminal behaves like no station at all, so a
     * job needing a grid pauses and a plan sees only the inventory, rather than starting work the station
     * could not feed. {@link #isStationMenu} stays a pure class check, for places that must recognise the
     * menu whatever its state (the final-grid hand-off, recipe-viewer registration).
     */
    public static boolean hasUsableStation(ServerPlayer player) {
        return open(player) != null;
    }

    /** Client mirror of the open station's counts, or null (see {@link Provider#clientCounts}). */
    public static Map<Item, Long> clientCounts(AbstractContainerMenu menu) {
        for (Provider provider : PROVIDERS) {
            Map<Item, Long> counts = provider.clientCounts(menu);
            if (counts != null) {
                return counts;
            }
        }
        return null;
    }

    public static List<StationMenu> menus() {
        List<StationMenu> menus = new ArrayList<>();
        for (Provider provider : PROVIDERS) {
            menus.addAll(provider.menus());
        }
        return menus;
    }
}
