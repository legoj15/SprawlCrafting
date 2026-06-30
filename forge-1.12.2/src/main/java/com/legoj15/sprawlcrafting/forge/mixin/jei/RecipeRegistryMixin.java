package com.legoj15.sprawlcrafting.forge.mixin.jei;

import com.google.common.collect.ImmutableTable;
import com.legoj15.sprawlcrafting.forge.compat.jei.DeferredHandlerRegistry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Overrides JEI's recipe-transfer handler lookup so SprawlCrafting's deferred-craft handler takes
 * priority over any other mod's handler for the same container class. JEI's registry is a flat
 * {@code Table<Class, String, Handler>} with exact-class keys and last-write-wins semantics; the
 * {@code @JEIPlugin} iteration order is an unordered {@code Set}, so we can't guarantee our
 * handler outlasts others. This Mixin intercepts the first {@code ImmutableTable.get()} call in
 * the resolution method and checks {@link DeferredHandlerRegistry} first — walking the class
 * hierarchy so every {@code ContainerWorkbench} subclass resolves to our handler automatically,
 * regardless of registration order.
 *
 * <p>Declared in a separate config ({@code mixins.sprawlcrafting.jei.json}) with
 * {@code required: false}, so a JEI-absent install loads fine — the target class simply isn't
 * found and the Mixin is silently skipped.
 */
@Mixin(targets = "mezz.jei.recipes.RecipeRegistry", remap = false)
public class RecipeRegistryMixin {

    @Redirect(
            method = "getRecipeTransferHandler",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableTable;get(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 0),
            require = 0)
    private Object sprawlcrafting$preferDeferredCraft(ImmutableTable<?, ?, ?> table,
                                                      Object rowKey, Object columnKey) {
        if (rowKey instanceof Class && "minecraft.crafting".equals(columnKey)) {
            Object ourHandler = DeferredHandlerRegistry.findHandler((Class<?>) rowKey);
            if (ourHandler != null) {
                return ourHandler;
            }
        }
        return table.get(rowKey, columnKey);
    }
}
