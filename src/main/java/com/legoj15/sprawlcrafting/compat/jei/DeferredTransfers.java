package com.legoj15.sprawlcrafting.compat.jei;

import java.util.List;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.GatherTooltip;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.client.ProtectedConfirm;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.RecipeIds;
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

    /**
     * What the arming click of a protected-only recipe returns (issue #53). USER_FACING so JEI
     * treats the click as "not transferred" and keeps its recipe screen open for the confirming
     * click; never returned from the hover pass, so the button itself stays active.
     */
    public static final IRecipeTransferError ARMED_CLICK = () -> IRecipeTransferError.Type.USER_FACING;

    /** The orange "what would I need to gather" button state for an unmakeable {@code recipe}. */
    public static IRecipeTransferError gatherListError(RecipeHolder<CraftingRecipe> recipe) {
        return new GatherListError(recipe);
    }

    /**
     * The same yellow state for a recipe only a protected-including plan can make (issue #40): the
     * tooltip adds which special items would be consumed, and a click arms the two-step confirm
     * (issue #53) — the second click on the same recipe sends the protected start.
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
     *         applies; else our yellow / orange cosmetic error (null once the click has sent — except a
     *         protected-only recipe's first click, which arms and returns the USER_FACING {@link #ARMED_CLICK},
     *         issue #53)
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
            // A recipe only the protected tier can make (issue #40) needs the player's explicit
            // consent, so its click arms the two-step confirm (issue #53): nothing is sent until
            // a second click on the same recipe. The arming click returns ARMED_CLICK, a USER_FACING
            // error: JEI closes its recipe screen whenever the click's result allows transfer
            // (null or COSMETIC), so only a non-transferring type keeps it open. The hover pass
            // still returns the cosmetic state, so the button stays live and turns red.
            boolean protectedCraft = DeferredCraftableCache.isProtectedOnly(holder);
            if (doTransfer) {
                if (protectedCraft) {
                    if (!ProtectedConfirm.confirmOrArm(RecipeIds.id(holder))) {
                        return ARMED_CLICK;
                    }
                    DeferredClickState.sendProtectedStartPacket(holder);
                } else {
                    DeferredClickState.sendStartPacket(holder);
                }
                return null;
            }
            return protectedCraft ? protectedDeferredAvailable(holder, GridContext.CRAFTING_TABLE)
                    : DEFERRED_AVAILABLE;
        }
        if (craftability == Craftability.UNSOLVABLE && SprawlConfig.get().needsSystem()
                && MissingIngredients.canRequestByRecipe()) {
            // Even deferred crafting can't make it from current resources. Repurpose JEI's otherwise
            // dead red button into a clickable "what would I need to gather" prompt (still available
            // mid-job, since it's informational). On the real click, open our own screen — deferred,
            // so it runs after JEI's click handling unwinds (no mid-click setScreen). The hover pass
            // returns the COSMETIC gather error; the click returns null (see below).
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

    /** Yellow (red while armed, #53) state for a protected-only recipe: the invite plus the special-items warning. */
    private static final class ProtectedDeferredError extends DeferredCraftError {

        /** Red while armed (issue #53): the next click spends the special items. */
        private static final int TRANSLUCENT_RED = 0x80FF3030;

        private final RecipeHolder<CraftingRecipe> recipe;
        private final GridContext grid;

        ProtectedDeferredError(RecipeHolder<CraftingRecipe> recipe, GridContext grid) {
            this.recipe = recipe;
            this.grid = grid;
        }

        @Override
        public int getButtonHighlightColor() {
            // Read live, not captured at construction: JEI may keep one error object across
            // frames, and the arm can expire (or be confirmed) between them.
            return ProtectedConfirm.isArmed(RecipeIds.id(recipe))
                    ? TRANSLUCENT_RED
                    : DeferredCraftError.TRANSLUCENT_YELLOW;
        }

        @Override
        public void getTooltip(ITooltipBuilder tooltip) {
            // Not super's lines: its "click to queue" would contradict the two-click confirm (#53).
            tooltip.add(Component.translatable("sprawlcrafting.recipe.deferred")
                    .withStyle(ChatFormatting.YELLOW));
            protectedWarningLines(recipe, grid).forEach(tooltip::add);
            tooltip.add(ProtectedConfirm.isArmed(RecipeIds.id(recipe))
                    ? ProtectedConfirm.armedLine()
                    : ProtectedConfirm.unarmedLine());
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
