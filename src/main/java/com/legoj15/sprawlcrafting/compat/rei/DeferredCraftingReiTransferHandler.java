package com.legoj15.sprawlcrafting.compat.rei;

import java.util.Optional;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.GridContext;

import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Shared REI deferred-craft transfer handler. Under the unified single-source tree this one
 * copy serves both loaders (Mojang mappings throughout; Loom remaps REI's intermediary API at
 * compile on Fabric, and the NeoForge node compiles it directly). The loader-specific plugin
 * that registers it lives in {@code platform.fabric.FabricReiPlugin} /
 * {@code platform.neoforge.NeoForgeReiPlugin}; all real logic stays in the shared
 * {@code client}/{@code craft} packages. REI ships on the 1.21.1 nodes only.
 */
public class DeferredCraftingReiTransferHandler implements TransferHandler {

    private static final int TRANSLUCENT_YELLOW = 0x80FFE000;
    private static final int TRANSLUCENT_ORANGE = 0x80FF6A00;

    @Override
    public double getPriority() {
        return 1.0; // ahead of the builtin handler (priority 0.0)
    }

    @Override
    public Result handle(Context context) {
        // REI offers a transfer button on both the 3×3 table (CraftingMenu) and the 2×2 inventory
        // grid (InventoryMenu); pick the grid context so deferred-craftability is judged per grid.
        GridContext grid;
        if (context.getMenu() instanceof CraftingMenu) {
            grid = GridContext.CRAFTING_TABLE;
        } else if (context.getMenu() instanceof InventoryMenu) {
            grid = GridContext.INVENTORY;
        } else {
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

        Craftability craftability = DeferredCraftableCache.classify(recipe, grid);
        if (craftability == Craftability.DEFERRED && !ClientJobTracker.hasActiveJob()) {
            if (context.isActuallyCrafting()) {
                DeferredClickState.sendStartPacket(recipe);
                return Result.createSuccessful();
            }
            // Only a "successful" result renders as a clickable transfer button in REI
            // (createFailed* are display-only); tint it yellow + attach the invite tooltip.
            return Result.createSuccessful()
                    .color(TRANSLUCENT_YELLOW)
                    .tooltip(Component.translatable("sprawlcrafting.recipe.deferred").withStyle(ChatFormatting.YELLOW))
                    .tooltip(Component.translatable("sprawlcrafting.recipe.deferred.click").withStyle(ChatFormatting.GRAY));
        }
        if (craftability == Craftability.UNSOLVABLE && grid == GridContext.CRAFTING_TABLE) {
            // Mirror JEI's orange button: an unmakeable recipe's transfer button opens the gather list
            // instead of REI's dead red "Not enough materials". Informational, so available mid-job.
            // Gated to the 3×3 table: on the 2×2, classify==UNSOLVABLE also covers "needs a bigger
            // grid", which isn't a gather case — the recipe book still serves the 2×2 gather list.
            // Open next tick so it lands after REI settles the click.
            if (context.isActuallyCrafting()) {
                // createSuccessful() leaves returningToScreen=false, so REI KEEPS its recipe screen
                // open — mc.screen next tick is the viewer, not the table. Capture the table from REI's
                // context now and make it the gather list's parent, so ESC returns to the table rather
                // than the viewer (otherwise the gather screen and the viewer ESC/E-loop forever).
                var table = context.getContainerScreen();
                MissingIngredients.requestOpenNextTick(
                        () -> MissingIngredientsView.open(recipe, GridContext.CRAFTING_TABLE, table));
                return Result.createSuccessful();
            }
            return Result.createSuccessful()
                    .color(TRANSLUCENT_ORANGE)
                    .tooltip(Component.translatable("sprawlcrafting.gather.header").withStyle(ChatFormatting.GOLD))
                    .tooltip(Component.translatable("sprawlcrafting.gather.click")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        }
        return Result.createNotApplicable();
    }
}
