package com.legoj15.sprawlcrafting.forge.client;

import com.legoj15.sprawlcrafting.forge.CommonProxy;

import org.apache.logging.log4j.LogManager;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/**
 * Client side of the sided proxy: registers the HUD flyout renderer and client-only event
 * handlers. Network message registration is handled entirely in {@code SprawlNetwork.initCommon()}.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        requireMixinBooter();
        super.preInit();
        MinecraftForge.EVENT_BUS.register(new DeferredPreviewRenderer());
        MinecraftForge.EVENT_BUS.register(new ServerPresence());
        MinecraftForge.EVENT_BUS.register(new CraftingScreenWatcher());
        MinecraftForge.EVENT_BUS.register(new PendingGatherScreen.Ticker());
        // Dev-only: RFG loads mod classes from build/classes/ but resources live in build/resources/,
        // so FMLFolderResourcePack can't serve the lang file. DevLangInjector injects from the
        // classpath instead. Production uses pack.mcmeta (pack_format 3) to avoid the
        // LegacyV2Adapter wrapping that would otherwise transform en_us → en_US lookups.
        if (FMLLaunchHandler.isDeobfuscatedEnvironment()) {
            MinecraftForge.EVENT_BUS.register(new DevLangInjector());
        }
    }

    /**
     * Every client-facing feature of this mod — the recipe-book highlight/click integration and the
     * JEI "+" handler priority — is implemented with Mixins. On 1.12.2 those Mixins are only ever
     * loaded by <a href="https://www.curseforge.com/minecraft/mc-mods/mixinbooter">MixinBooter</a>,
     * which is the sole thing that reads this jar's {@code MixinConfigs} manifest attribute and
     * bootstraps a modern (0.8.5) Mixin runtime. Without it the mod loads but every Mixin silently
     * no-ops, leaving a half-working install (only JEI "+" on containers registered by exact class
     * still functions) — a failure mode that is very hard to diagnose from the symptom alone.
     *
     * <p>So fail loudly instead: hard-stop a production client with a clear message, shown on
     * Forge's custom-error screen ({@link MissingMixinBooterException}) rather than as a crash
     * report a modpack player has to dig through. This lives in the <em>client</em> proxy on
     * purpose — a dedicated server uses no Mixins (every config here is client-only), so it must
     * not require MixinBooter. Dev/CI runs (deobfuscated) only warn, since a classpath-provided
     * MixinBooter may not be registered as a loadable mod there.
     */
    private static void requireMixinBooter() {
        if (Loader.isModLoaded("mixinbooter")) {
            return;
        }
        String message = "SprawlCrafting's recipe-book and JEI integration are implemented with "
                + "Mixins, which on 1.12.2 are loaded by MixinBooter. MixinBooter is not installed, so "
                + "those features are inactive. Install MixinBooter "
                + "(https://www.curseforge.com/minecraft/mc-mods/mixinbooter) and restart.";
        LogManager.getLogger("SprawlCrafting").error("**** {}", message);
        if (!FMLLaunchHandler.isDeobfuscatedEnvironment()) {
            throw new MissingMixinBooterException(message);
        }
    }
}
