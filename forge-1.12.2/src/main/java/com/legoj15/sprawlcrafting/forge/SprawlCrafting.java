package com.legoj15.sprawlcrafting.forge;

import com.legoj15.sprawlcrafting.forge.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.12.2 entry point for SprawlCrafting — the legacy-loader counterpart of the modern
 * Fabric/NeoForge entry points. Wires the shared craft engine: the network channel, the per-tick
 * job driver (via the sided proxy), and shutdown cleanup.
 *
 * <p>All gameplay logic lives in {@code com.legoj15.sprawlcrafting.forge.craft} on top of the
 * shared, pure-Java-8 {@code com.legoj15.sprawlcrafting.craft.solver} core (compiled in from
 * ../solver-core).
 */
@Mod(
        modid = SprawlCrafting.MOD_ID,
        name = SprawlCrafting.MOD_NAME,
        version = BuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        // Optional mod: "*" accepts any remote version (including the mod being absent), so a server
        // running SprawlCrafting won't reject vanilla/unmodded clients, and a client running it can
        // still join servers without it. Without this, FML defaults to requiring an exact version
        // match on both sides. Safe here because the server only sends on the channel in response to
        // a client-initiated craft (CraftQueueManager.tick early-returns when the player has no job),
        // so a peer that lacks the mod never receives an unsolicited packet.
        acceptableRemoteVersions = "*"
)
public class SprawlCrafting {

    public static final String MOD_ID = "sprawlcrafting";
    public static final String MOD_NAME = "SprawlCrafting";
    // The version lives in the generated BuildInfo (com.legoj15.sprawlcrafting.forge.BuildInfo), sourced
    // from the root gradle.properties so there is a single place to bump it repo-wide. @Mod(version=...)
    // above references BuildInfo.VERSION (a generated compile-time constant).

    @SidedProxy(
            clientSide = "com.legoj15.sprawlcrafting.forge.client.ClientProxy",
            serverSide = "com.legoj15.sprawlcrafting.forge.CommonProxy"
    )
    public static CommonProxy proxy;

    private static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        SprawlNetwork.initCommon();
        proxy.preInit();
        logger.info("{} {} loaded on Minecraft 1.12.2 (Forge).", MOD_NAME, BuildInfo.VERSION);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        CraftQueueManager.clearAll();
    }
}
