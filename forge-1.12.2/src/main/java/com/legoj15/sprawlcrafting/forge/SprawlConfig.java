package com.legoj15.sprawlcrafting.forge;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The 1.12.2 face of the modern config (config/sprawlcrafting.json + in-game screen): Forge's
 * annotation config system gives the file (config/sprawlcrafting.cfg) AND the mod-list "Config"
 * button for free ({@code DefaultGuiFactory} builds the GUI for {@code @Config} mods). Three of
 * modern's four toggles — REI doesn't exist on 1.12.2. All consumers are client-side and read the
 * static fields live, mirroring modern's gate composition: the yellow deferred JEI button rides
 * {@link #jeiIntegration}; the orange gather button and every "what am I missing" surface ride
 * {@link #jeiIntegration} AND/or {@link #needsSystem}; the step sound rides {@link #soundEffects}.
 */
@Config(modid = SprawlCrafting.MOD_ID)
public class SprawlConfig {

    @Config.Name("Sound effects")
    @Config.LangKey("sprawlcrafting.config.sound")
    @Config.Comment("Play a sound for each completed step of a sprawl-craft.")
    public static boolean soundEffects = true;

    @Config.Name("JEI integration")
    @Config.LangKey("sprawlcrafting.config.jei")
    @Config.Comment("Master switch for SprawlCrafting's JEI additions: the yellow deferred \"+\" "
            + "button and its red missing-slot highlights. Off, JEI's \"+\" only moves/fills "
            + "directly craftable recipes.")
    public static boolean jeiIntegration = true;

    @Config.Name("Needs helper")
    @Config.LangKey("sprawlcrafting.config.needs")
    @Config.Comment("The \"what am I missing\" helper: the missing-resources screen, the orange "
            + "JEI gather button, and the recipe-book hints that lead to them.")
    public static boolean needsSystem = true;

    /** Persists in-game edits from the mod-list Config screen back to disk. Client-only event. */
    @Mod.EventBusSubscriber(modid = SprawlCrafting.MOD_ID, value = Side.CLIENT)
    private static class Sync {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (SprawlCrafting.MOD_ID.equals(event.getModID())) {
                ConfigManager.sync(SprawlCrafting.MOD_ID, Config.Type.INSTANCE);
            }
        }
    }
}
