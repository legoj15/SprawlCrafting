package com.legoj15.sprawlcrafting.platform;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.services.IPlatformHelper;

// Resolves the loader-specific platform helper for the shared code. Under Stonecutter's
// unified single source tree the concrete impl is picked at preprocess time by the active
// node's loader constant — no ServiceLoader / META-INF/services file is needed (and the
// other loader's class is excluded from compilation by the per-node build script).
public class Services {

    public static final IPlatformHelper PLATFORM = createPlatformHelper();

    private static IPlatformHelper createPlatformHelper() {
        IPlatformHelper helper =
                //? if fabric {
                new com.legoj15.sprawlcrafting.platform.fabric.FabricPlatformHelper();
                //?} else {
                /*new com.legoj15.sprawlcrafting.platform.neoforge.NeoForgePlatformHelper();*/
                //?}
        Constants.LOG.debug("Loaded {} for service {}", helper, IPlatformHelper.class);
        return helper;
    }
}
