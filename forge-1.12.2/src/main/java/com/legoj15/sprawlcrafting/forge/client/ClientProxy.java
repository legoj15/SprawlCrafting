package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.CommonProxy;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/**
 * Client side of the sided proxy: registers the HUD flyout renderer and client-only event
 * handlers. Network message registration is handled entirely in {@code SprawlNetwork.initCommon()}.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        super.preInit();
        MinecraftForge.EVENT_BUS.register(new HudOverlay());
        MinecraftForge.EVENT_BUS.register(new DeferredPreviewRenderer());
        // Dev-only: RFG loads mod classes from build/classes/ but resources live in build/resources/,
        // so FMLFolderResourcePack can't serve the lang file. DevLangInjector injects from the
        // classpath instead. Production uses pack.mcmeta (pack_format 3) to avoid the
        // LegacyV2Adapter wrapping that would otherwise transform en_us → en_US lookups.
        if (FMLLaunchHandler.isDeobfuscatedEnvironment()) {
            MinecraftForge.EVENT_BUS.register(new DevLangInjector());
        }
    }
}
