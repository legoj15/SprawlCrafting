package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.CommonClass;
import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.DeferredCraftSync;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge entry point (common to both sides). Forge has no per-dist {@code @Mod} like NeoForge, so the
 * client-only setup lives in {@link ForgeClient} and is reached through a {@link FMLEnvironment#dist}
 * guard, keeping client classes from loading on a dedicated server.
 */
@Mod(Constants.MOD_ID)
public class ForgeMod {

    public ForgeMod(FMLJavaModLoadingContext context) {
        CommonClass.init();

        // Channels must exist before the registry locks, i.e. during construction.
        ForgeNetwork.register();

        MinecraftForge.EVENT_BUS.addListener(ForgeMod::onServerTickPost);
        MinecraftForge.EVENT_BUS.addListener(ForgeMod::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ForgeMod::onServerStopping);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClient.init(context);
        }
    }

    // End-of-server-tick player loop, deliberately mirroring the Fabric/NeoForge wiring so jobs
    // tick identically on every loader (PlayerTickEvent skips players whose entity tick is
    // suppressed, e.g. spectators in unloaded chunks — this does not).
    private static void onServerTickPost(TickEvent.ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CraftQueueManager.tick(player);
            DeferredCraftSync.maybeSync(player); // no-op on 1.21.1
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CraftQueueManager.clear(event.getEntity().getUUID());
        DeferredCraftSync.clear(event.getEntity().getUUID()); // no-op on 1.21.1
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CraftQueueManager.clearAll();
        DeferredCraftSync.clearAll(); // no-op on 1.21.1; prevents a cross-world debounce leak on 26.x
    }
}
