package com.legoj15.sprawlcrafting.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

/**
 * Minimal Forge 1.12.2 entry point for SprawlCrafting.
 *
 * <p>This is the legacy-loader counterpart to the modern Fabric/NeoForge entry points in
 * the Stonecutter tree. For now it is only a scaffold: it loads, logs once, and proves the
 * module compiles against the deobfuscated 1.12.2 Forge classpath produced by
 * RetroFuturaGradle. The deferred-craft behaviour will be ported on top of the shared
 * {@code com.legoj15.sprawlcrafting.craft.solver} core (compiled in from ../solver-core).
 */
@Mod(
        modid = SprawlCrafting.MOD_ID,
        name = SprawlCrafting.MOD_NAME,
        version = SprawlCrafting.VERSION,
        acceptedMinecraftVersions = "[1.12.2]"
)
public class SprawlCrafting {

    public static final String MOD_ID = "sprawlcrafting";
    public static final String MOD_NAME = "SprawlCrafting";
    public static final String VERSION = "1.0.1";

    private static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("{} {} loaded on Minecraft 1.12.2 (Forge).", MOD_NAME, VERSION);
    }
}
