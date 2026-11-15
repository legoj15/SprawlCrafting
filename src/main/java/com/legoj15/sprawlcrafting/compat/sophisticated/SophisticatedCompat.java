package com.legoj15.sprawlcrafting.compat.sophisticated;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.craft.CraftingGrids;

/**
 * Optional integration with Sophisticated Core (the library under Sophisticated Storage and Sophisticated
 * Backpacks): their crafting upgrade is a 3×3 crafting grid, so SprawlCrafting treats it like a crafting table.
 *
 * <p>Soft dependency: nothing outside this package refers to it except {@code CommonClass} (behind the
 * {@code sophisticated} Stonecutter constant and a {@code sophisticatedcore} loaded check) and the JEI plugin
 * (same two guards); no class here may load, and no payload exists, unless Sophisticated Core is installed.
 * The common code sees only {@link CraftingGrids}; this package adds the provider that resolves the upgrade's
 * grid, and {@link SophisticatedJei} fronts the upgrade's JEI transfer handlers.
 */
public final class SophisticatedCompat {

    public static final String CORE_MOD_ID = "sophisticatedcore";
    public static final String STORAGE_MOD_ID = "sophisticatedstorage";
    public static final String BACKPACKS_MOD_ID = "sophisticatedbackpacks";

    private SophisticatedCompat() {
    }

    /** Registers the crafting-upgrade grid resolver. Call only with Sophisticated Core loaded. */
    public static void init() {
        CraftingGrids.register(new SophisticatedGrids());
        Constants.LOG.info("Sophisticated Core found: crafting upgrade grids count as crafting tables");
    }
}
