package com.legoj15.sprawlcrafting;

import com.legoj15.sprawlcrafting.platform.Services;
import com.legoj15.solver.RecipeGraphSolver;

// Loader-neutral init, called from every loader's entrypoint (FabricMod, NeoForgeMod, ForgeMod).
public class CommonClass {

    public static void init() {
        // Resolve the bundled solver library (com.legoj15:solver, carried jar-in-jar) at mod init, not at
        // the first craft: a runtime that lacks it (a dev run missing the classpath wiring, or a loader
        // that failed to load the nested jar) then fails every boot test with NoClassDefFoundError right
        // here. The class literal is what forces the load. See docs/solver-library.md.
        Class<?> solver = RecipeGraphSolver.class;
        Package pkg = solver.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        Constants.LOG.info("SprawlCrafting init on {} ({}); solver library {} {} loaded",
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName(),
                solver.getName(), version != null ? version : "(version not in manifest)");
    }
}
