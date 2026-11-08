package com.legoj15.sprawlcrafting;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Mod identity shared by both 1.16.5 loaders. 1.16.5 ships Log4j 2 (no SLF4J on the game classpath). */
public final class Constants {

    public static final String MOD_ID = "sprawlcrafting";
    public static final String MOD_NAME = "SprawlCrafting";
    public static final Logger LOG = LogManager.getLogger(MOD_NAME);

    private Constants() {
    }

    public static net.minecraft.util.ResourceLocation id(String path) {
        return new net.minecraft.util.ResourceLocation(MOD_ID, path);
    }
}
