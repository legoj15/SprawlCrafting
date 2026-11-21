package com.legoj15.sprawlcrafting.compat.jei;

import java.util.List;

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

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * What a crafting-grid transfer handler does once its own (direct) transfer has been tried: turn a
 * user-facing "missing items" error into the yellow deferred-craft offer or the orange gather-list
 * button. One decision for every handler that fronts a 3×3 grid, the vanilla crafting table
 * ({@link DeferredCraftingTransferHandler}) and the Sophisticated crafting upgrade
 * ({@code compat.sophisticated}) alike, so the two cannot drift apart.
 */
public final class DeferredTransfers {

    /** The yellow "craft it for me" button state; also used by station handlers (compat/ae2). */
    public static final IRecipeTransferError DEFERRED_AVAILABLE = new DeferredCraftError();

    /** The orange "what would I need to gather" button state for an unmakeable {@code recipe}. */
    public static IRecipeTransferError gatherListError(RecipeHolder<CraftingRecipe> recipe) {
        return new GatherListError(recipe);
    }

    /**
     * The same yellow state for a recipe only a protected-including plan can make (issue #40): the
     * tooltip adds which special items would be consumed, and a click sends the protected start.
     * Shared with the station handler (compat/ae2); REI gets the same lines through
     * {@code client.ProtectedWarnings};
     * {@code grid} costs the warning lines' plan, as at the clicked grid.
     */
    public static IRecipeTransferError protectedDeferredAvailable(RecipeHolder<CraftingRecipe> recipe,
                                                                  GridContext grid) {
        return new ProtectedDeferredError(recipe, grid);
    }

    private DeferredTransfers() {
    }

    /**
     * @param inner      what the grid's own handler returned for the same click (null = it transferred directly)
     * @param holder     the recipe being transferred
     * @param doTransfer false while JEI is only asking how to draw the button, true on the click
     * @return {@code inner} unchanged when it is a direct transfer, an internal error, or nothing of ours
     *         applies; else our yellow / orange cosmetic error (or null once the click has been handled)
     */
    public static IRecipeTransferError resolve(IRecipeTransferError inner, RecipeHolder<CraftingRecipe> holder,
                                               boolean doTransfer) {
        if (inner == null || inner.getType() != IRecipeTransferError.Type.USER_FACING) {
            return inner; // direct transfer worked (or an internal error we don't own)
        }
        if (!SprawlConfig.get().jeiIntegration()) {
            return inner; // JEI integration disabled: leave JEI's own (red) error untouched
        }
        Craftability craftability = DeferredCraftableCache.classify(holder, GridContext.CRAFTING_TABLE);
        if (craftability == Craftability.DEFERRED) {
            if (ClientJobTracker.hasActiveJob()) {
                return inner; // busy: don't offer to queue another deferred craft
            }
            // A recipe only the protected tier can make (issue #40) confirms through the protected
            // start packet, and its tooltip says which special items the plan would consume.
            boolean protectedCraft = DeferredCraftableCache.isProtectedOnly(holder);
            if (doTransfer) {
                if (protectedCraft) {
                    DeferredClickState.sendProtectedStartPacket(holder);
                } else {
                    DeferredClickState.sendStartPacket(holder);
                }
                return null;
            }
            return protectedCraft ? protectedDeferredAvailable(holder, GridContext.CRAFTING_TABLE)
                    : DEFERRED_AVAILABLE;
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
                        () -> MissingIngredientsView.open(holder, GridContext.CRAFTING_TABLE));
                return null;
            }
            return new GatherListError(holder);
        }
        return inner;
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

    /** Yellow state for a protected-only recipe: the deferred invite plus the special-items warning. */
    private static final class ProtectedDeferredError extends DeferredCraftError {

        private final RecipeHolder<CraftingRecipe> recipe;
        private final GridContext grid;

        ProtectedDeferredError(RecipeHolder<CraftingRecipe> recipe, GridContext grid) {
            this.recipe = recipe;
            this.grid = grid;
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            super.getTooltip(tooltip);
            protectedWarningLines(recipe, grid).forEach(tooltip::add);
        }
    }

    /**
     * The special-items warning lines for {@code recipe}'s current plan at {@code grid} —
     * {@link com.legoj15.sprawlcrafting.client.ProtectedWarnings#lines}, the JEI-free home both
     * viewers share (JEI is only suggested, so the REI path must not class-load JEI statics).
     */
    public static List<Component> protectedWarningLines(RecipeHolder<CraftingRecipe> recipe, GridContext grid) {
        return com.legoj15.sprawlcrafting.client.ProtectedWarnings.lines(recipe, grid);
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
