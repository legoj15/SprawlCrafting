package com.legoj15.sprawlcrafting.platform.forge;

import com.legoj15.sprawlcrafting.Constants;

/**
 * Mixin self-check for dev runs and agents (port of legacy-1.16.5's {@code MixinSelfCheck}). The
 * recipe-book mixin targets normally load only when a player opens a recipe book, so a
 * boot-to-title-screen run never applies them. With {@code SC_MIXIN_SELFTEST=1} in the environment this
 * loads every target class (without initialising it) at client start, which makes Mixin apply, and with
 * {@code defaultRequire = 1} fail loudly on, every injection. Off unless the variable is set; players
 * never set it. Lives in platform/forge so only the Forge jars carry it.
 */
final class ForgeMixinSelfCheck {

    private static final String ENV = "SC_MIXIN_SELFTEST";

    private ForgeMixinSelfCheck() {
    }

    static void runIfRequested() {
        if (!"1".equals(System.getenv(ENV))) {
            return;
        }
        ClassLoader loader = ForgeMixinSelfCheck.class.getClassLoader();
        // Every class named in sprawlcrafting.mixins.json (Mojmap = SRG class names from 1.17 on). Strings,
        // not class literals, so a target that fails to transform is caught below instead of escaping.
        String[] targets = {
                "net.minecraft.client.gui.screens.recipebook.RecipeBookComponent",
                "net.minecraft.client.gui.screens.recipebook.RecipeBookPage",
                "net.minecraft.client.gui.screens.recipebook.RecipeButton",
                "net.minecraft.client.gui.screens.recipebook.RecipeCollection",
                "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent",
                "net.minecraft.client.gui.screens.recipebook.OverlayRecipeComponent$OverlayRecipeButton"
        };
        int failed = 0;
        for (String target : targets) {
            try {
                Class.forName(target, false, loader);
            } catch (Throwable t) {
                failed++;
                Constants.LOG.error("SprawlCrafting mixin self-check FAILED for {}", target, t);
            }
        }
        if (failed == 0) {
            Constants.LOG.info("SprawlCrafting mixin self-check passed: {} target classes loaded", targets.length);
        }
    }
}
