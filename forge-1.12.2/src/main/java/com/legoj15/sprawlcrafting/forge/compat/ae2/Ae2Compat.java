package com.legoj15.sprawlcrafting.forge.compat.ae2;

import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Applied Energistics 2 integration entry point (issue #2: the Crafting Terminal is a crafting
 * station whose material includes the ME network it shows). Called once from
 * {@code SprawlCrafting.preInit}, and only after {@code Loader.isModLoaded("appliedenergistics2")}:
 * this package is the only code that names an {@code appeng} type, so an AE2-less pack never loads
 * one. Works against both original AE2 rv6 and the AE2 Unofficial Extended Life fork (same modid,
 * same package); every AE2 member used here was checked to exist with the same signature in
 * {@code appliedenergistics2-rv6-stable-7} and {@code ae2-uel-v0.56.7}, and the two private/protected
 * client fields that differ in visibility are read by reflection ({@link Ae2ClientRepo}).
 */
public final class Ae2Compat {

    private Ae2Compat() {
    }

    public static void init() {
        NetworkTerminals.register(new Ae2TerminalProvider());
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            // Client only: remembers the terminal screen under JEI's recipe screen (Ae2ClientRepo).
            MinecraftForge.EVENT_BUS.register(new Ae2ClientRepo.ScreenTracker());
        }
    }
}
