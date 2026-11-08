package com.legoj15.sprawlcrafting.platform;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.services.IPlatformHelper;

// Resolves the loader-specific platform helper for the shared code. Under Stonecutter's
// unified single source tree the concrete impl is picked at preprocess time by the active
// node's loader constant (fabric / forge / otherwise NeoForge) — no ServiceLoader /
// META-INF/services file is needed (and the other loaders' classes are excluded from compilation
// by the per-node build scripts).
public class Services {

    public static final IPlatformHelper PLATFORM = createPlatformHelper();

    private static IPlatformHelper createPlatformHelper() {
        IPlatformHelper helper =
                //? if fabric {
                new com.legoj15.sprawlcrafting.platform.fabric.FabricPlatformHelper();
                //?} else if forge {
                /*new com.legoj15.sprawlcrafting.platform.forge.ForgePlatformHelper();*/
                //?} else {
                /*new com.legoj15.sprawlcrafting.platform.neoforge.NeoForgePlatformHelper();*/
                //?}
        Constants.LOG.debug("Loaded {} for service {}", helper, IPlatformHelper.class);
        return helper;
    }
}
