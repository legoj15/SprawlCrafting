package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.platform.Services;

/**
 * Dev-run mixin self-check for agents (see docs/legacy-1.16.5.md, smoke.ps1). The recipe-book mixin
 * targets normally load only when the player opens a recipe book, so a boot-to-title-screen run would
 * never apply them. With {@code SC_MIXIN_SELFTEST=1} in the environment this loads every target class
 * (without initialising it) at client start, which makes Mixin apply, and with {@code defaultRequire = 1}
 * fail loudly on, every injection. Off by default; never runs for players.
 */
public final class MixinSelfCheck {

    private static final String ENV = "SC_MIXIN_SELFTEST";

    private MixinSelfCheck() {
    }

    public static void runIfRequested() {
        if (!"1".equals(System.getenv(ENV))) {
            return;
        }
        ClassLoader loader = MixinSelfCheck.class.getClassLoader();
        // Dev class names (Forge-style on both loaders, thanks to the Fabric class-name layer). Strings,
        // not class literals, so a target that fails to transform is caught below instead of escaping.
        String[] targets = {
                "net.minecraft.client.gui.recipebook.RecipeBookGui",
                "net.minecraft.client.gui.recipebook.RecipeBookPage",
                "net.minecraft.client.gui.recipebook.RecipeList",
                "net.minecraft.client.gui.recipebook.RecipeOverlayGui",
                "net.minecraft.client.gui.recipebook.RecipeWidget",
                "net.minecraft.client.gui.recipebook.RecipeOverlayGui$RecipeButtonWidget",
                "mezz.jei.gui.recipes.RecipeTransferButton"
        };
        int loaded = 0;
        int failed = 0;
        for (String target : targets) {
            if (target.startsWith("mezz.jei.") && !Services.PLATFORM.isModLoaded("jei")) {
                continue;
            }
            try {
                Class.forName(target, false, loader);
                loaded++;
            } catch (Throwable t) {
                failed++;
                Constants.LOG.error("SprawlCrafting mixin self-check FAILED for {}", target, t);
            }
        }
        if (failed == 0) {
            Constants.LOG.info("SprawlCrafting mixin self-check passed: {} target classes loaded", loaded);
        }
    }
}
