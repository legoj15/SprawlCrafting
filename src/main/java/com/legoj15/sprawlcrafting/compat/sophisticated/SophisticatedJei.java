package com.legoj15.sprawlcrafting.compat.sophisticated;

import com.legoj15.sprawlcrafting.platform.Services;

import mezz.jei.api.registration.IRecipeTransferRegistration;

/**
 * Entry point for the JEI side of the Sophisticated integration, called from
 * {@code SprawlCraftingJeiPlugin.registerRecipeTransferHandlers}. Only JEI types appear here; the helpers that
 * name Sophisticated Storage / Backpacks classes are reached only behind a loaded check, so this class (and
 * the plugin that calls it) load fine without them.
 *
 * <p>Why re-register at all: JEI keeps one handler per (menu class, recipe type) and the last registration
 * wins, in mod-list order. Storage and Backpacks each register a plain handler for their menu; ours goes in
 * after them (the optional {@code AFTER} dependencies in the mod metadata make FML order us last), wraps
 * an identical instance of the Core handler, and adds the deferred / gather-list offers on top
 * ({@link SophisticatedCraftingTransferHandler}).
 */
public final class SophisticatedJei {

    private SophisticatedJei() {
    }

    public static void registerTransferHandlers(IRecipeTransferRegistration registration) {
        if (!Services.PLATFORM.isModLoaded(SophisticatedCompat.CORE_MOD_ID)) {
            return;
        }
        if (Services.PLATFORM.isModLoaded(SophisticatedCompat.STORAGE_MOD_ID)) {
            SophisticatedStorageJei.register(registration);
        }
        if (Services.PLATFORM.isModLoaded(SophisticatedCompat.BACKPACKS_MOD_ID)) {
            SophisticatedBackpacksJei.register(registration);
        }
    }
}
