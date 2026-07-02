package com.legoj15.sprawlcrafting.forge.mixin.jei;

import com.google.common.collect.ImmutableTable;
import com.legoj15.sprawlcrafting.forge.compat.jei.DeferredHandlerRegistry;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

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
            Class<?> containerClass = (Class<?>) rowKey;
            Object ourHandler = DeferredHandlerRegistry.findHandler(containerClass);
            if (ourHandler == null) {
                ourHandler = sprawlcrafting$structuralFallback(containerClass);
            }
            if (ourHandler != null) {
                return ourHandler;
            }
        }
        return table.get(rowKey, columnKey);
    }

    /**
     * When no handler is registered for {@code containerClass}, use the class-less structural
     * fallback if the player's <em>open</em> container is exactly this class and reads as a 3x3
     * crafter (an unknown modded crafting station). JEI resolves the transfer handler by the open
     * GUI's container class, so the open container is the right — and only — instance to inspect.
     * The decision is re-derived from that live instance on every query (a cheap slot scan) rather
     * than memoised into {@link DeferredHandlerRegistry#register}: memoising would key the forced-3x3
     * handler by class in the hierarchy-walked handler map, so a differently-shaped <em>subclass</em>
     * (e.g. a 2x2 variant extending a 3x3 station) would later inherit it with no structural re-check.
     * Returns null (defer to JEI's own table) when JEI's fallback is unset or the class is not the
     * open, structurally-3x3 container.
     */
    private static Object sprawlcrafting$structuralFallback(Class<?> containerClass) {
        Object fallback = DeferredHandlerRegistry.structuralFallback();
        if (fallback == null) {
            return null;
        }
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) {
            return null;
        }
        Container open = player.openContainer;
        if (open != null && open.getClass() == containerClass
                && GridContext.isThreeByThreeCrafter(open)) {
            return fallback;
        }
        return null;
    }
}
