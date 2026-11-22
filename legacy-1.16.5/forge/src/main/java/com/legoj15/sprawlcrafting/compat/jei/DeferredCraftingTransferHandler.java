package com.legoj15.sprawlcrafting.compat.jei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.legoj15.sprawlcrafting.client.ClientJobTracker;
import com.legoj15.sprawlcrafting.client.DeferredClickState;
import com.legoj15.sprawlcrafting.client.DeferredCraftableCache;
import com.legoj15.sprawlcrafting.client.GatherTooltip;
import com.legoj15.sprawlcrafting.client.MissingIngredients;
import com.legoj15.sprawlcrafting.client.MissingIngredientsView;
import com.legoj15.sprawlcrafting.client.ProtectedConfirm;
import com.legoj15.sprawlcrafting.config.SprawlConfig;
import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.Craftability;
import com.legoj15.sprawlcrafting.craft.CraftPreview;
import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.ShortfallView;
import com.mojang.blaze3d.matrix.MatrixStack;

import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.WorkbenchContainer;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * Replaces JEI's builtin transfer handler for the crafting table screen. Direct transfers (all
 * ingredients present) delegate to JEI's own logic untouched; when JEI would show the red "Missing
 * items" error but the recipe is solvable from raw resources, the "+" button turns yellow and stays
 * clickable instead, and clicking it sends the same start packet the recipe book uses. An unmakeable
 * recipe gets an orange clickable button that opens the gather list.
 *
 * <p>JEI 7 has no per-error button colour API: a COSMETIC error is filled with a fixed amber. The
 * yellow/orange split comes from {@code JeiTransferButtonMixin}, which asks {@link #highlightColor}.
 */
public class DeferredCraftingTransferHandler implements IRecipeTransferHandler<WorkbenchContainer> {

    private static final IRecipeTransferError DEFERRED_AVAILABLE = new DeferredCraftError();

    /**
     * What the arming click of a protected-only recipe returns (issue #53). USER_FACING so JEI
     * treats the click as "not transferred" and keeps its recipe screen open for the confirming
     * click; never returned from the hover pass, so the button itself stays active.
     */
    static final IRecipeTransferError ARMED_CLICK = new IRecipeTransferError() {
        @Override
        public Type getType() {
            return Type.USER_FACING;
        }

        @Override
        public void showError(MatrixStack poseStack, int mouseX, int mouseY, IRecipeLayout layout,
                              int recipeX, int recipeY) {
            // Never drawn: the hover pass returns the cosmetic protected state instead.
        }
    };

    private final IRecipeTransferHandler<WorkbenchContainer> vanilla;

    public DeferredCraftingTransferHandler(IRecipeTransferHandler<WorkbenchContainer> vanilla) {
        this.vanilla = vanilla;
    }

    /** The yellow "craftable from raw resources" state, shared with {@link StationTransferHandler}. */
    static IRecipeTransferError deferredAvailable() {
        return DEFERRED_AVAILABLE;
    }

    /**
     * The same yellow state for a recipe only a protected-including plan can make (issue #40): the
     * tooltip adds which special items would be consumed, and a click arms the two-step confirm
     * (issue #53) — the second click on the same recipe sends the protected start.
     * Shared with {@link StationTransferHandler}.
     */
    static IRecipeTransferError protectedDeferredAvailable(IRecipe<?> recipe, GridContext grid) {
        return new ProtectedDeferredError(recipe, grid);
    }

    /**
     * The orange "open the gather list" state for {@code recipe}, its tooltip costed for {@code grid}; shared
     * with {@link StationTransferHandler}.
     */
    static IRecipeTransferError gatherListError(IRecipe<?> recipe, GridContext grid) {
        return new GatherListError(recipe, grid);
    }

    /** The button tint for one of our errors, or {@code fallback} for anyone else's. */
    public static int highlightColor(IRecipeTransferError error, int fallback) {
        if (error instanceof ColoredError) {
            return ((ColoredError) error).color();
        }
        return fallback;
    }

    @Override
    public Class<WorkbenchContainer> getContainerClass() {
        return WorkbenchContainer.class;
    }

    @Override
    public IRecipeTransferError transferRecipe(WorkbenchContainer container, Object recipe, IRecipeLayout layout,
                                               PlayerEntity player, boolean maxTransfer, boolean doTransfer) {
        IRecipeTransferError vanillaError =
                vanilla.transferRecipe(container, recipe, layout, player, maxTransfer, doTransfer);
        if (vanillaError == null || vanillaError.getType() != IRecipeTransferError.Type.USER_FACING) {
            return vanillaError; // direct transfer worked (or an internal error we don't own)
        }
        if (!SprawlConfig.get().jeiIntegration() || !(recipe instanceof IRecipe)) {
            return vanillaError; // integration disabled (or a non-vanilla recipe object): JEI's red error
        }
        final IRecipe<?> craft = (IRecipe<?>) recipe;
        Craftability craftability = DeferredCraftableCache.classify(craft, GridContext.CRAFTING_TABLE);
        if (craftability == Craftability.DEFERRED) {
            if (ClientJobTracker.hasActiveJob()) {
                return vanillaError; // busy: don't offer to queue another deferred craft
            }
            // A recipe only the protected tier can make (issue #40) needs the player's explicit
            // consent, so its click arms the two-step confirm (issue #53): nothing is sent until
            // a second click on the same recipe. The arming click returns ARMED_CLICK, a USER_FACING
            // error: JEI closes its recipe screen whenever the click's result allows transfer
            // (null or COSMETIC), so only a non-transferring type keeps it open.
            boolean protectedCraft = DeferredCraftableCache.isProtectedOnly(craft);
            if (doTransfer) {
                if (protectedCraft) {
                    if (!ProtectedConfirm.confirmOrArm(craft.getId())) {
                        return ARMED_CLICK;
                    }
                    DeferredClickState.sendProtectedStartPacket(craft);
                } else {
                    DeferredClickState.sendStartPacket(craft);
                }
                return null;
            }
            return protectedCraft ? protectedDeferredAvailable(craft, GridContext.CRAFTING_TABLE)
                    : DEFERRED_AVAILABLE;
        }
        if (craftability == Craftability.UNSOLVABLE && SprawlConfig.get().needsSystem()) {
            if (doTransfer) {
                // The click triggers JEI's own GUI teardown, so opening our screen inline is swept away
                // with it. Queue the open for upcoming client ticks (MissingIngredients#pollPendingOpen).
                // Return null so JEI goes back to the crafting table rather than staying open.
                MissingIngredients.requestOpenNextTick(
                        () -> MissingIngredientsView.open(craft, GridContext.CRAFTING_TABLE));
                return null;
            }
            return new GatherListError(craft, GridContext.CRAFTING_TABLE);
        }
        return vanillaError;
    }

    /** A COSMETIC (still clickable) error carrying its own button tint and tooltip. */
    private abstract static class ColoredError implements IRecipeTransferError {

        abstract int color();

        abstract List<ITextComponent> tooltip();

        @Override
        public Type getType() {
            return Type.COSMETIC;
        }

        @Override
        public void showError(MatrixStack poseStack, int mouseX, int mouseY, IRecipeLayout layout,
                              int recipeX, int recipeY) {
            Screen screen = Minecraft.getInstance().screen;
            if (screen != null) {
                screen.renderComponentTooltip(poseStack, tooltip(), mouseX, mouseY);
            }
        }
    }

    /** Yellow state inviting the deferred craft. */
    private static final class DeferredCraftError extends ColoredError {

        private static final int TRANSLUCENT_YELLOW = 0x80FFE000;

        @Override
        int color() {
            return TRANSLUCENT_YELLOW;
        }

        @Override
        List<ITextComponent> tooltip() {
            List<ITextComponent> lines = new ArrayList<>();
            lines.add(new TranslationTextComponent("sprawlcrafting.recipe.deferred").withStyle(TextFormatting.YELLOW));
            lines.add(new TranslationTextComponent("sprawlcrafting.recipe.deferred.click").withStyle(TextFormatting.GRAY));
            return lines;
        }
    }

    /** Yellow (red while armed, #53) state for a protected-only recipe: the invite plus the special-items warning. */
    private static final class ProtectedDeferredError extends ColoredError {

        /** Red while armed (issue #53): the next click spends the special items. */
        private static final int TRANSLUCENT_RED = 0x80FF3030;

        private final IRecipe<?> recipe;
        private final GridContext grid;

        ProtectedDeferredError(IRecipe<?> recipe, GridContext grid) {
            this.recipe = recipe;
            this.grid = grid;
        }

        @Override
        int color() {
            // Read live, not captured at construction: the mixin asks every frame, and the arm
            // can expire (or be confirmed) between two frames.
            return ProtectedConfirm.isArmed(recipe.getId())
                    ? TRANSLUCENT_RED : DeferredCraftError.TRANSLUCENT_YELLOW;
        }

        @Override
        List<ITextComponent> tooltip() {
            List<ITextComponent> lines = new ArrayList<>();
            lines.add(new TranslationTextComponent("sprawlcrafting.recipe.deferred").withStyle(TextFormatting.YELLOW));
            // No "click to queue" line: the confirm line below asks for two clicks (#53).
            lines.addAll(protectedWarningLines(recipe, grid));
            lines.add(ProtectedConfirm.isArmed(recipe.getId())
                    ? ProtectedConfirm.armedLine() : ProtectedConfirm.unarmedLine());
            return lines;
        }
    }

    /**
     * The special-items warning lines for {@code recipe}'s current plan, or empty when the live
     * inventory no longer needs any (the button then says the plain deferred thing only). Same
     * per-hover cost as the gather tooltip's shortfall: one plan against the cached session. The
     * open station's plain counts join as plain material: the executor draws them before any
     * protected copy, so without them an item the station supplies anyway would be over-warned; its
     * protected stacks (issue #49) join as protected copies instead.
     */
    static List<ITextComponent> protectedWarningLines(IRecipe<?> recipe, GridContext grid) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return Collections.emptyList();
        }
        CraftPlanner.PlanOutcome outcome = DeferredCraftableCache.plan(recipe, grid);
        if (!outcome.isPlanned() || !outcome.job().usesProtected()) {
            return Collections.emptyList();
        }
        return CraftPreview.protectedWarningLines(outcome.job(), minecraft.level.getRecipeManager(),
                minecraft.player.inventory, DeferredCraftableCache.stationCounts(),
                DeferredCraftableCache.stationProtectedCounts(),
                DeferredCraftableCache.stationProtectedSamples());
    }

    /** Orange state for an unmakeable recipe: clicking opens the gather list. */
    private static final class GatherListError extends ColoredError {

        // Distinct from the deferred yellow: orange reads as "can't make this, here's what you'd need".
        private static final int TRANSLUCENT_ORANGE = 0x80FF6A00;

        private final IRecipe<?> recipe;
        private final GridContext grid;

        GatherListError(IRecipe<?> recipe, GridContext grid) {
            this.recipe = recipe;
            this.grid = grid;
        }

        @Override
        int color() {
            return TRANSLUCENT_ORANGE;
        }

        @Override
        List<ITextComponent> tooltip() {
            ShortfallView view = MissingIngredients.shortfallLocal(recipe, grid);
            return GatherTooltip.compact(view.demands(), view.approximate());
        }
    }
}
