package com.legoj15.sprawlcrafting.forge.craft;

import java.util.List;

import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner.PlanOutcome;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * The single server-side entry point for starting a deferred craft, shared by the
 * {@code /sprawlcrafting craft} command, the JEI transfer hook, and (later) the recipe book.
 * Plans with the player's current grid context and enforces the single-job rule.
 */
public final class CraftRequests {

    private CraftRequests() {
    }

    public abstract static class StartOutcome {
        private StartOutcome() {
        }

        public static final class Started extends StartOutcome {
            private final CraftJob job;

            public Started(CraftJob job) {
                this.job = job;
            }

            public CraftJob job() {
                return job;
            }
        }

        public static final class Busy extends StartOutcome {
        }

        public static final class Rejected extends StartOutcome {
            private final PlanOutcome outcome;

            public Rejected(PlanOutcome outcome) {
                this.outcome = outcome;
            }

            public PlanOutcome outcome() {
                return outcome;
            }
        }
    }

    /** Start path keyed by a specific recipe (command / recipe-book click). */
    public static StartOutcome tryStart(EntityPlayerMP player, IRecipe recipe) {
        return tryStart(player, recipe, false);
    }

    /**
     * {@link #tryStart(EntityPlayerMP, IRecipe)} with the protected tier (issue #40):
     * {@code allowProtected} is the confirmed request from the protected channel, whose plan may
     * also count the player's protected stacks (enchanted and/or renamed, never damaged) once the
     * plain passes — the inventory and station chest, then an open terminal's network — have fallen
     * short. A request that still fails keeps the plain passes' rejection: the message says what is
     * missing, not which tier ran out.
     */
    public static StartOutcome tryStart(EntityPlayerMP player, IRecipe recipe, boolean allowProtected) {
        if (CraftQueueManager.activeJob(player.getUniqueID()).isPresent()) {
            return new StartOutcome.Busy();
        }
        // Vanilla parity: return the grid's contents to the inventory first, so the planner (which
        // reads only the 36 main slots) can see items that were sitting in the grid.
        CraftExecutor.clearOpenCraftingGrid(player);
        GridContext grid = GridContext.current(player);
        PlanOutcome own = CraftPlanner.sessionWithoutNetwork(player, grid).plan(recipe);
        boolean plannedOnNetwork = false;
        if (own instanceof PlanOutcome.Unsolvable || own instanceof PlanOutcome.TooComplex) {
            if (NetworkTerminals.usable(player)) {
                own = CraftPlanner.plan(player, recipe, grid);
                plannedOnNetwork = own instanceof PlanOutcome.Planned;
            }
            if (!(own instanceof PlanOutcome.Planned) && allowProtected
                    && CraftPlanner.hasProtected(player.inventory)) {
                // The confirmed fallback (#40): count the protected stacks of the 36 main slots too,
                // in the same order as the plain passes — without the network first, so a job they
                // cover never depends on it, then with an open terminal's counts, which makes the
                // job network-dependent (it pauses, not cancels, when the terminal goes away).
                PlanOutcome withProtected = CraftPlanner.sessionWithoutNetwork(player, grid, true).plan(recipe);
                if (!(withProtected instanceof PlanOutcome.Planned) && NetworkTerminals.usable(player)) {
                    withProtected = CraftPlanner.plan(player, recipe, grid, true);
                    plannedOnNetwork = withProtected instanceof PlanOutcome.Planned;
                }
                if (withProtected instanceof PlanOutcome.Planned) {
                    ((PlanOutcome.Planned) withProtected).job().setUsesProtected(true);
                    own = withProtected;
                }
            }
        }
        return finish(player, own, plannedOnNetwork);
    }

    /** Start path keyed by a result item (the JEI "+" hook, which has the stack, not the recipe). */
    public static StartOutcome tryStartByResult(EntityPlayerMP player, ItemStack result) {
        return tryStartByResult(player, result, false);
    }

    /** {@link #tryStartByResult(EntityPlayerMP, ItemStack)} with the protected tier (issue #40);
     *  see {@link #tryStart(EntityPlayerMP, IRecipe, boolean)}. */
    public static StartOutcome tryStartByResult(EntityPlayerMP player, ItemStack result,
                                                boolean allowProtected) {
        if (CraftQueueManager.activeJob(player.getUniqueID()).isPresent()) {
            return new StartOutcome.Busy();
        }
        CraftExecutor.clearOpenCraftingGrid(player);
        GridContext grid = GridContext.current(player);
        PlanOutcome own = CraftPlanner.sessionWithoutNetwork(player, grid).planByResult(result);
        boolean plannedOnNetwork = false;
        if (own instanceof PlanOutcome.Unsolvable || own instanceof PlanOutcome.TooComplex) {
            if (NetworkTerminals.usable(player)) {
                own = CraftPlanner.planByResult(player, result, grid);
                plannedOnNetwork = own instanceof PlanOutcome.Planned;
            }
            if (!(own instanceof PlanOutcome.Planned) && allowProtected
                    && CraftPlanner.hasProtected(player.inventory)) {
                PlanOutcome withProtected = CraftPlanner.sessionWithoutNetwork(player, grid, true)
                        .planByResult(result);
                if (!(withProtected instanceof PlanOutcome.Planned) && NetworkTerminals.usable(player)) {
                    withProtected = CraftPlanner.planByResult(player, result, grid, true);
                    plannedOnNetwork = withProtected instanceof PlanOutcome.Planned;
                }
                if (withProtected instanceof PlanOutcome.Planned) {
                    ((PlanOutcome.Planned) withProtected).job().setUsesProtected(true);
                    own = withProtected;
                }
            }
        }
        return finish(player, own, plannedOnNetwork);
    }

    /**
     * Inventory-first, as in the root tree: a job the player's own items (plus grid and station chest)
     * cover is planned without the network and never depends on it, so closing the terminal later does
     * not pause it; only a job that needed the network ({@code plannedOnNetwork}) starts dependent. Any
     * job still becomes dependent the first time a craft draws from or stores overflow in the network.
     */
    private static StartOutcome finish(EntityPlayerMP player, PlanOutcome outcome, boolean plannedOnNetwork) {
        if (outcome instanceof PlanOutcome.Planned) {
            CraftJob job = ((PlanOutcome.Planned) outcome).job();
            // Remember what was in the pool at plan time, so a later step that can't be satisfied
            // once that source is gone waits for it instead of cancelling on an item it simply can't
            // currently see: a station chest (open station) and, separately, a network terminal's
            // network (AE2), the latter only when the plan needed it. A job can also become
            // dependent later (CraftQueueManager flags what a craft consumed or stored).
            job.setChestDependent(ExternalSlots.present(player));
            job.setNetworkDependent(plannedOnNetwork);
            CraftQueueManager.start(player, job);
            return new StartOutcome.Started(job);
        }
        return new StartOutcome.Rejected(outcome);
    }

    /** Packet-path handler (recipe id): resolves, starts, and reports via the action bar. */
    public static void handleStartByRecipe(EntityPlayerMP player, ResourceLocation recipeId) {
        handleStartByRecipe(player, recipeId, false);
    }

    /**
     * {@link #handleStartByRecipe(EntityPlayerMP, ResourceLocation)} with the protected tier
     * (issue #40): the confirmed request from {@code StartProtectedCraftMessage}, which the client
     * only sends for a recipe its protected session could plan.
     */
    public static void handleStartByRecipe(EntityPlayerMP player, ResourceLocation recipeId,
                                           boolean allowProtected) {
        if (player.isSpectator()) {
            return;
        }
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            notify(player, coloured(new TextComponentTranslation("sprawlcrafting.craft.unknown_recipe",
                    recipeId.toString()), TextFormatting.RED));
            return;
        }
        report(player, tryStart(player, recipe, allowProtected), recipe.getRecipeOutput().getDisplayName());
    }

    /** Packet-path handler (result item): the JEI hook. */
    public static void handleStartByResult(EntityPlayerMP player, ItemStack result) {
        handleStartByResult(player, result, false);
    }

    /** {@link #handleStartByResult(EntityPlayerMP, ItemStack)} with the protected tier (issue #40);
     *  see {@link #handleStartByRecipe(EntityPlayerMP, ResourceLocation, boolean)}. */
    public static void handleStartByResult(EntityPlayerMP player, ItemStack result, boolean allowProtected) {
        if (player.isSpectator() || result.isEmpty()) {
            return;
        }
        report(player, tryStartByResult(player, result, allowProtected), result.getDisplayName());
    }

    private static void report(EntityPlayerMP player, StartOutcome outcome, String targetName) {
        ITextComponent feedback;
        if (outcome instanceof StartOutcome.Started) {
            CraftJob job = ((StartOutcome.Started) outcome).job();
            feedback = new TextComponentTranslation("sprawlcrafting.craft.started",
                    job.targetResult().getDisplayName(),
                    com.legoj15.solver.CountFormat.format(job.totalCrafts()));
        } else if (outcome instanceof StartOutcome.Busy) {
            feedback = coloured(new TextComponentTranslation("sprawlcrafting.craft.busy"), TextFormatting.RED);
        } else {
            feedback = describeRejection(((StartOutcome.Rejected) outcome).outcome(), targetName);
        }
        notify(player, feedback);
    }

    /** Builds the red rejection feedback. {@code targetName} names the item that couldn't be planned. */
    public static ITextComponent describeRejection(PlanOutcome outcome, String targetName) {
        ITextComponent body;
        if (outcome instanceof PlanOutcome.Unsupported) {
            body = new TextComponentTranslation("sprawlcrafting.craft.unsupported", targetName);
        } else if (outcome instanceof PlanOutcome.NeedsBiggerGrid) {
            body = new TextComponentTranslation("sprawlcrafting.craft.needs_table", targetName);
        } else if (outcome instanceof PlanOutcome.TooComplex) {
            body = new TextComponentTranslation("sprawlcrafting.craft.too_complex", targetName);
        } else if (outcome instanceof PlanOutcome.Unsolvable) {
            List<ItemKey> missing = ((PlanOutcome.Unsolvable) outcome).missing();
            // Nothing missing: every pass was rejected on replay — the materials are there, but no
            // order of crafts works, and a "not enough resources" message would be false.
            body = missing.isEmpty()
                    ? new TextComponentTranslation("sprawlcrafting.craft.unsolvable_order", targetName)
                    : new TextComponentTranslation("sprawlcrafting.craft.unsolvable", targetName,
                            missingNames(missing));
        } else {
            body = new TextComponentString("?");
        }
        return coloured(body, TextFormatting.RED);
    }

    private static String missingNames(List<ItemKey> missing) {
        if (missing.isEmpty()) {
            return "?";
        }
        StringBuilder builder = new StringBuilder();
        for (ItemKey key : missing) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(key.toStack().getDisplayName());
        }
        return builder.toString();
    }

    private static ITextComponent coloured(ITextComponent component, TextFormatting color) {
        component.getStyle().setColor(color);
        return component;
    }

    private static void notify(EntityPlayerMP player, ITextComponent message) {
        player.sendStatusMessage(message, false);
    }
}
