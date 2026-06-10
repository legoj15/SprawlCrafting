package com.legoj15.sprawlcrafting;

import com.legoj15.sprawlcrafting.command.SprawlCraftingCommand;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(Constants.MOD_ID)
public class SprawlCrafting {

    public SprawlCrafting(IEventBus eventBus) {
        CommonClass.init();

        // Gameplay hooks all live on the game bus, not the mod bus we were handed.
        NeoForge.EVENT_BUS.addListener(SprawlCrafting::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(SprawlCrafting::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(SprawlCrafting::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(SprawlCrafting::onServerStopping);
    }

    // End-of-server-tick player loop, deliberately mirroring the Fabric wiring so jobs
    // tick identically on both loaders (PlayerTickEvent skips players whose entity tick
    // is suppressed, e.g. spectators in unloaded chunks — this does not).
    private static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CraftQueueManager.tick(player);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        SprawlCraftingCommand.register(event.getDispatcher());
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        CraftQueueManager.clear(event.getEntity().getUUID());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CraftQueueManager.clearAll();
    }
}
