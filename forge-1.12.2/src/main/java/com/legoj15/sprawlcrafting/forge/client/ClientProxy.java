package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.CommonProxy;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/**
 * Client side of the sided proxy: registers the S2C progress handler (loading its client-only
 * handler class here keeps it off a dedicated server) and the HUD flyout renderer.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        super.preInit();
        SprawlNetwork.initClient();
        MinecraftForge.EVENT_BUS.register(new HudOverlay());
        MinecraftForge.EVENT_BUS.register(new DeferredPreviewRenderer());
        // Dev-only: FML doesn't serve our lang from the classpath dev jar in runClient, so inject
        // it directly. Production installs load the lang normally and never need this.
        if (FMLLaunchHandler.isDeobfuscatedEnvironment()) {
            MinecraftForge.EVENT_BUS.register(new DevLangInjector());
        }
    }
}
