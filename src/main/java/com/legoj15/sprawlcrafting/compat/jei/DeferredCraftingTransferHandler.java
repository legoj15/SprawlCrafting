package com.legoj15.sprawlcrafting.compat.jei;

import java.util.Optional;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.GatherTooltip;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.ShortfallView;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
//? if >=1.21.11 {
/*import mezz.jei.api.recipe.types.IRecipeType;*/
//?} else {
import mezz.jei.api.recipe.RecipeType;
//?}
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Replaces JEI's builtin transfer handler for the crafting table screen. Direct
 * transfers (all ingredients present) delegate to JEI's own logic untouched; when JEI
 * would show the red "Missing items" error but the recipe is solvable from raw
 * resources, the transfer button turns yellow and inviting instead — clicking it sends
 * the same start packet the recipe book uses.
 */
public class DeferredCraftingTransferHandler
        implements IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> {

    private static final IRecipeTransferError DEFERRED_AVAILABLE = new DeferredCraftError();

    private final IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> vanilla;

    public DeferredCraftingTransferHandler(
            IRecipeTransferHandler<CraftingMenu, RecipeHolder<CraftingRecipe>> vanilla) {
        this.vanilla = vanilla;
    }

    @Override
    public Class<? extends CraftingMenu> getContainerClass() {
        return CraftingMenu.class;
    }

    @Override
    public Optional<MenuType<CraftingMenu>> getMenuType() {
        return Optional.of(MenuType.CRAFTING);
    }

    @Override
    //? if >=1.21.11 {
    /*public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {*/
    //?} else {
    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
    //?}
        return RecipeTypes.CRAFTING;
    }

    @Override
    public IRecipeTransferError transferRecipe(CraftingMenu container, RecipeHolder<CraftingRecipe> recipe,
                                               IRecipeSlotsView recipeSlots, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        IRecipeTransferError vanillaError =
                vanilla.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer);
        if (vanillaError == null || vanillaError.getType() != IRecipeTransferError.Type.USER_FACING) {
            return vanillaError; // direct transfer worked (or an internal error we don't own)
        }
        if (!SprawlConfig.get().jeiIntegration()) {
            return vanillaError; // JEI integration disabled: leave JEI's own (red) error untouched
        }
        Craftability craftability = DeferredCraftableCache.classify(recipe, GridContext.CRAFTING_TABLE);
        if (craftability == Craftability.DEFERRED) {
            if (ClientJobTracker.hasActiveJob()) {
                return vanillaError; // busy: don't offer to queue another deferred craft
            }
            if (doTransfer) {
                DeferredClickState.sendStartPacket(recipe);
                return null;
            }
            return DEFERRED_AVAILABLE;
        }
        if (craftability == Craftability.UNSOLVABLE && SprawlConfig.get().needsSystem()) {
            // Even deferred crafting can't make it from current resources. Repurpose JEI's otherwise
            // dead red button into a clickable "what would I need to gather" prompt (still available
            // mid-job, since it's informational). On the real click, open our own screen — deferred,
            // so it runs after JEI's click handling unwinds (no mid-click setScreen). Always return a
            // non-null COSMETIC error, never null: null is JEI's "transfer succeeded" signal and makes
            // it close its recipe GUI (synchronously on 26.x's JEI, deferred on 1.21.1's), which would
            // clobber the screen we just opened.
            if (doTransfer) {
                // The click triggers JEI's own GUI teardown (on 1.21.1's JEI it closes everything),
                // so opening inline or via Minecraft.execute gets swept away with it. Queue the open
                // for upcoming client ticks instead — it re-asserts our screen after JEI fully settles
                // (see MissingIngredients#pollPendingOpen). Return null like the deferred path so JEI
                // returns to the crafting table rather than kicking the player out.
                MissingIngredients.requestOpenNextTick(
                        () -> MissingIngredientsView.open(recipe, GridContext.CRAFTING_TABLE));
                return null;
            }
            return new GatherListError(recipe);
        }
        return vanillaError;
    }

    /** Cosmetic (still clickable) yellow state inviting the deferred craft. */
    private static class DeferredCraftError implements IRecipeTransferError {

        private static final int TRANSLUCENT_YELLOW = 0x80FFE000;

        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public int getButtonHighlightColor() {
            return TRANSLUCENT_YELLOW;
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("sprawlcrafting.recipe.deferred")
                    .withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.translatable("sprawlcrafting.recipe.deferred.click")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** Clickable orange state for an unmakeable recipe: clicking opens the gather list. */
    private static class GatherListError implements IRecipeTransferError {

        // Distinct from the deferred yellow (0x80FFE000): orange reads as "can't make this — here's
        // what you'd need" rather than the yellow "click and I'll craft it for you".
        private static final int TRANSLUCENT_ORANGE = 0x80FF6A00;

        private final RecipeHolder<CraftingRecipe> recipe;

        GatherListError(RecipeHolder<CraftingRecipe> recipe) {
            this.recipe = recipe;
        }

        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public int getButtonHighlightColor() {
            return TRANSLUCENT_ORANGE;
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            //? if >=1.21.11 {
            /*// 26.x can't solve locally; show the lightweight hint and fetch the full list on click.
            GatherTooltip.hint().forEach(tooltip::add);*/
            //?} else {
            ShortfallView view = MissingIngredients.shortfallLocal(recipe, GridContext.CRAFTING_TABLE);
            GatherTooltip.compact(view.demands(), view.approximate()).forEach(tooltip::add);
            //?}
        }
    }
}
