package com.legoj15.sprawlcrafting.forge;

import java.util.Map;
import javax.annotation.Nullable;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * An empty FML coremod. It exists ONLY so the jar manifest can carry {@code FMLCorePlugin} plus
 * {@code FMLCorePluginContainsFMLMod} (see the {@code jar} block in build.gradle), which makes FML itself
 * load this jar onto the classpath and mark it "reparseable": still scanned for {@code @Mod} once, from the
 * mods directory, but skipped by FML's classpath scan.
 *
 * <p>Without it the jar is not a coremod, so MixinBooter's discovery (driven by the manifest's
 * {@code MixinConfigs} attribute) adds it to the LaunchClassLoader on its own, by the RELATIVE path it builds
 * from {@code Launch.minecraftHome}. A dedicated server launched the canonical way
 * ({@code java -jar forge-....jar nogui}, no {@code --gameDir}) then has FML scan the same jar a second time
 * as a classpath source, at an absolute path, and {@code Loader.identifyDuplicates} refuses to start
 * ({@code DuplicateModsFoundException}). MixinBooter skips the classloader add for any jar FML already lists
 * as a reparseable coremod, so with this class the jar is found exactly once. (Issue #41.)
 *
 * <p>No transformers, no setup class, no access transformer, no side effects: it must stay that way, since it
 * is instantiated by the LaunchClassLoader before any Minecraft class is loadable, on both sides.
 */
@IFMLLoadingPlugin.Name("SprawlCrafting")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class SprawlCraftingCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    @Nullable
    public String getModContainerClass() {
        return null;
    }

    @Override
    @Nullable
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
