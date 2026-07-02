package com.legoj15.sprawlcrafting.forge.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraftforge.fml.client.CustomModLoadingErrorDisplayException;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The missing-MixinBooter hard stop, rendered the way 1.12.2 Forge intends fixable dependency
 * problems to be reported: a plain in-game error screen with instructions, instead of a crash
 * report a modpack player has to dig the one relevant line out of. Forge shows this via
 * {@code GuiCustomModLoadingErrorScreen}, which draws the background and delegates entirely to
 * {@link #drawScreen}. Thrown only from the client proxy (the class is client-only, and a
 * dedicated server must not require MixinBooter).
 */
@SideOnly(Side.CLIENT)
public class MissingMixinBooterException extends CustomModLoadingErrorDisplayException {

    private static final int LINE_HEIGHT = 12;
    private static final String[] LINES = {
            "SprawlCrafting could not start",
            "",
            "SprawlCrafting's recipe-book and JEI integration on 1.12.2",
            "are built on Mixins, which are loaded by the mod MixinBooter —",
            "and MixinBooter is not installed.",
            "",
            "Install MixinBooter for 1.12.2 and restart the game:",
            "curseforge.com/minecraft/mc-mods/mixinbooter",
            "or modrinth.com/mod/mixinbooter",
    };

    public MissingMixinBooterException(String message) {
        // The plain-text message still lands in the log alongside the ClientProxy error line,
        // for reports where only the log survives.
        super(message, null);
    }

    @Override
    public void initGui(GuiErrorScreen errorScreen, FontRenderer fontRenderer) {
    }

    @Override
    public void drawScreen(GuiErrorScreen errorScreen, FontRenderer fontRenderer,
                           int mouseRelX, int mouseRelY, float tickTime) {
        int y = errorScreen.height / 2 - LINES.length * LINE_HEIGHT / 2;
        for (int i = 0; i < LINES.length; i++) {
            int color = i == 0 ? 0xFFE65C5C          // title: red
                    : i >= LINES.length - 3 ? 0xFFE6D24D  // the "install it" lines: yellow
                    : 0xFFFFFFFF;
            errorScreen.drawCenteredString(fontRenderer, LINES[i],
                    errorScreen.width / 2, y + i * LINE_HEIGHT, color);
        }
    }
}
