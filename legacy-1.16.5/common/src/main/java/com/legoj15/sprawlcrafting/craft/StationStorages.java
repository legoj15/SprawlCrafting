package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.Services;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.Item;

/**
 * Registry of crafting-station providers: menus that are a 3×3 crafting grid AND show an item storage
 * whose contents count as craft material while the menu is open (issue #29, the AE2 Crafting Terminal).
 * Shared code asks this class; it never names a compat class, so it compiles and runs unchanged on both
 * loaders, with or without the mod behind a provider.
 *
 * <p>Providers are registered at mod init by {@link #bootstrap()}, only when the owning mod is loaded,
 * and reached by reflection: the compat package lives in the Forge source set only, so the Fabric jar
 * does not contain it and a direct reference would not compile there. With no provider registered
 * every query is a constant-time no-op.
 */
public final class StationStorages {

    /** One station type, implemented in compat code. */
    public interface Provider {

        /**
         * Server: the storage behind the station {@code player} has open, or null when none is open or it is
         * unusable right now (e.g. an ME terminal whose network is offline or out of power). "Usable" is
         * judged live, every call: an unusable station is treated exactly like a closed one (no counts, no
         * draws, no grid), see {@link StationStorages#hasUsableStation}. A throw here reads as null.
         */
        StationStorage open(ServerPlayerEntity player);

        /**
         * Client: plain-item counts of the storage {@code menu} shows (the client's mirror of
         * {@link StationStorage#counts()}), or null when {@code menu} is not a usable station. Called at
         * most once per client tick (the caller caches).
         */
        Map<Item, Long> clientCounts(Container menu);

        /** Every station menu class, for recipe-viewer transfer-handler registration (JEI 7 keys by class). */
        List<Class<? extends Container>> menus();
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    /** Providers whose {@code open} has already thrown and been logged (once each, not every tick). */
    private static final java.util.Set<Provider> FAILED_OPEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private StationStorages() {
    }

    /**
     * Registers every station provider whose mod is loaded. Called once from each loader's mod init. A
     * provider class missing from this jar (the Fabric jar has no compat/ae2) is skipped silently; any
     * other failure is logged and the mod carries on without that station.
     */
    public static void bootstrap() {
        // AE2 8.x (1.16.5) still uses its long mod id; the "ae2" id is 1.18+.
        tryRegister("appliedenergistics2", "com.legoj15.sprawlcrafting.compat.ae2.Ae2Station");
    }

    private static void tryRegister(String modId, String providerClass) {
        if (!Services.PLATFORM.isModLoaded(modId)) {
            return;
        }
        try {
            Class.forName(providerClass).getMethod("init").invoke(null);
            Constants.LOG.info("SprawlCrafting: {} crafting stations enabled", modId);
        } catch (ClassNotFoundException e) {
            // This loader's jar has no compat for that mod: nothing to enable.
        } catch (ReflectiveOperationException | LinkageError e) {
            Constants.LOG.warn("SprawlCrafting: could not enable {} crafting stations", modId, e);
        }
    }

    /** Called by a provider's {@code init()}. */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    /** The storage behind the station the player has open right now, or null. */
    public static StationStorage open(ServerPlayerEntity player) {
        for (Provider provider : PROVIDERS) {
            StationStorage storage;
            try {
                storage = provider.open(player);
            } catch (RuntimeException | LinkageError e) {
                // Runs every server tick for every player with a job: a throwing station mod must read as
                // "no usable station" (the job pauses) rather than abort the tick loop for later players. Only
                // this entry check is isolated; calls on a storage it returned are not.
                if (FAILED_OPEN.add(provider)) {
                    Constants.LOG.warn("SprawlCrafting: crafting station check failed, treating it as closed", e);
                }
                continue;
            }
            if (storage != null) {
                return storage;
            }
        }
        return null;
    }

    /**
     * Whether the player has a station open that is usable right now. This decides whether the open
     * station counts as a 3×3 grid (the reach gate in {@code CraftQueueManager} and
     * {@code GridContext.current}): an open but offline terminal behaves like no station at all, so a
     * job needing a grid pauses and a plan sees only the inventory, rather than starting work the station
     * could not feed.
     */
    public static boolean hasUsableStation(ServerPlayerEntity player) {
        return open(player) != null;
    }

    /** Client mirror of the open station's counts, or null (see {@link Provider#clientCounts}). */
    public static Map<Item, Long> clientCounts(Container menu) {
        for (Provider provider : PROVIDERS) {
            Map<Item, Long> counts = provider.clientCounts(menu);
            if (counts != null) {
                return counts;
            }
        }
        return null;
    }

    public static List<Class<? extends Container>> menus() {
        List<Class<? extends Container>> menus = new ArrayList<>();
        for (Provider provider : PROVIDERS) {
            menus.addAll(provider.menus());
        }
        return menus;
    }
}
