package com.legoj15.sprawlcrafting.compat.rei;

import java.util.Optional;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.GridContext;

import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Fabric copy of the REI deferred-craft transfer handler — byte-identical to the NeoForge
 * one (both written in Mojang mappings; Loom remaps REI's intermediary API at compile).
 * REI publishes no Mojang-mapped common API jar, so this small glue class is duplicated
 * per loader; all real logic stays in the shared {@code client}/{@code craft} packages.
 * See the NeoForge copy for full documentation.
 */
public class DeferredCraftingReiTransferHandler implements TransferHandler {

    private static final int TRANSLUCENT_YELLOW = 0x80FFE000;

    @Override
    public double getPriority() {
        return 1.0; // ahead of the builtin handler (priority 0.0)
    }

    @Override
    public Result handle(Context context) {
        if (!(context.getMenu() instanceof CraftingMenu)) {
            return Result.createNotApplicable();
        }
        Optional<ResourceLocation> displayId = context.getDisplay().getDisplayLocation();
        if (displayId.isEmpty() || context.getMinecraft().level == null) {
            return Result.createNotApplicable();
        }
        RecipeHolder<?> holder = context.getMinecraft().level.getRecipeManager()
                .byKey(displayId.get()).orElse(null);
        if (holder == null || !(holder.value() instanceof CraftingRecipe)) {
            return Result.createNotApplicable();
        }
        @SuppressWarnings("unchecked")
        RecipeHolder<CraftingRecipe> recipe = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder;

        if (ClientJobTracker.hasActiveJob()
                || DeferredCraftableCache.classify(recipe, GridContext.CRAFTING_TABLE) != Craftability.DEFERRED) {
            return Result.createNotApplicable();
        }
        if (context.isActuallyCrafting()) {
            DeferredClickState.sendStartPacket(recipe);
            return Result.createSuccessful();
        }
        return Result.createFailedCustomButtonColor(
                        Component.translatable("sprawlcrafting.recipe.deferred").withStyle(ChatFormatting.YELLOW),
                        TRANSLUCENT_YELLOW)
                .tooltip(Component.translatable("sprawlcrafting.recipe.deferred.click")
                        .withStyle(ChatFormatting.GRAY))
                .blocksFurtherHandling();
    }
}
