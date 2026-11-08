package com.legoj15.sprawlcrafting.mixin.jei;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Adds the JEI mixins only when JEI is in the mod list. The config JSON lists no mixins itself, so with
 * JEI absent Mixin never even looks up the JEI target class (no "target not found" noise or failure).
 */
public class JeiMixinPlugin implements IMixinConfigPlugin {

    private boolean jeiPresent;

    @Override
    public void onLoad(String mixinPackage) {
        LoadingModList mods = LoadingModList.get();
        jeiPresent = mods != null && mods.getModFileById("jei") != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return jeiPresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return jeiPresent ? Collections.singletonList("JeiTransferButtonMixin") : null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
