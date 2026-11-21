package com.legoj15.sprawlcrafting.client;

import java.util.List;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.CraftPreview;
import com.legoj15.sprawlcrafting.craft.GridContext;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The special-items warning lines (issue #40) a recipe viewer hangs on the yellow deferred button
 * of a protected-only recipe. Deliberately JEI-free: JEI is only a suggested dependency, and the
 * JEI-side home of the shared button states ({@code compat.jei.DeferredTransfers}) holds
 * JEI-typed statics, so merely touching it from an REI-only install would throw
 * {@code NoClassDefFoundError} when the class initializes. Both viewers (and JEI's own error
 * class, by delegation) come through here so the two cannot drift apart.
 */
public final class ProtectedWarnings {

    private ProtectedWarnings() {
    }

    /**
     * The special-items warning lines for {@code recipe}'s current plan at {@code grid}, or empty
     * when the live inventory no longer needs any (the button then says the plain deferred thing
     * only). Same per-hover cost as the gather tooltip's shortfall: one plan against the cached
     * session. 26.x cannot solve locally (the sync only carries ids, no item names), so there the
     * tooltip is the header plus a see-the-preview hint — the preview itself carries the full lines.
     */
    public static List<Component> lines(RecipeHolder<CraftingRecipe> recipe, GridContext grid) {
        //? if >=1.21.11 {
        /*return java.util.List.of(
                Component.translatable("sprawlcrafting.preview.protected_header").withStyle(ChatFormatting.GOLD),
                Component.translatable("sprawlcrafting.preview.protected_hint")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));*/
        //?} else {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return List.of();
        }
        if (!(DeferredCraftableCache.plan(recipe, grid)
                instanceof CraftPlanner.PlanOutcome.Planned planned)) {
            return List.of();
        }
        return CraftPreview.protectedWarningLines(planned.job(), minecraft.level.getRecipeManager(),
                minecraft.level.registryAccess(), minecraft.player.getInventory(),
                DeferredCraftableCache.stationCounts());
        //?}
    }
}
