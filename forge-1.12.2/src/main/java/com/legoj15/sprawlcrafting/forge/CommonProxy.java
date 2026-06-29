package com.legoj15.sprawlcrafting.forge;

import net.minecraftforge.common.MinecraftForge;

/**
 * Server/common side of the mod's sided proxy. Registers the per-tick + logout event handler that
 * drives the deferred-craft engine. The client proxy extends this to add the HUD and the S2C
 * progress handler.
 */
public class CommonProxy {

    public void preInit() {
        MinecraftForge.EVENT_BUS.register(new ServerEvents());
    }
}
