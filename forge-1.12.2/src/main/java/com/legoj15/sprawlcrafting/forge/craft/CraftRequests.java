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
        if (CraftQueueManager.activeJob(player.getUniqueID()).isPresent()) {
            return new StartOutcome.Busy();
        }
        // Vanilla parity: return the grid's contents to the inventory first, so the planner (which
        // reads only the 36 main slots) can see items that were sitting in the grid.
        CraftExecutor.clearOpenCraftingGrid(player);
        return finish(player, CraftPlanner.plan(player, recipe, GridContext.current(player)));
    }

    /** Start path keyed by a result item (the JEI "+" hook, which has the stack, not the recipe). */
    public static StartOutcome tryStartByResult(EntityPlayerMP player, ItemStack result) {
        if (CraftQueueManager.activeJob(player.getUniqueID()).isPresent()) {
            return new StartOutcome.Busy();
        }
        CraftExecutor.clearOpenCraftingGrid(player);
        return finish(player, CraftPlanner.planByResult(player, result, GridContext.current(player)));
    }

    private static StartOutcome finish(EntityPlayerMP player, PlanOutcome outcome) {
        if (outcome instanceof PlanOutcome.Planned) {
            CraftJob job = ((PlanOutcome.Planned) outcome).job();
            // Remember whether a station chest was in the pool at plan time, so a later step that
            // can't be satisfied while the station is closed waits for it to reopen instead of
            // cancelling on a chest item it simply can't currently see.
            job.setExternalDependent(ExternalSlots.present(player));
            CraftQueueManager.start(player, job);
            return new StartOutcome.Started(job);
        }
        return new StartOutcome.Rejected(outcome);
    }

    /** Packet-path handler (recipe id): resolves, starts, and reports via the action bar. */
    public static void handleStartByRecipe(EntityPlayerMP player, ResourceLocation recipeId) {
        if (player.isSpectator()) {
            return;
        }
        IRecipe recipe = CraftingManager.getRecipe(recipeId);
        if (recipe == null) {
            notify(player, coloured(new TextComponentTranslation("sprawlcrafting.craft.unknown_recipe",
                    recipeId.toString()), TextFormatting.RED));
            return;
        }
        report(player, tryStart(player, recipe), recipe.getRecipeOutput().getDisplayName());
    }

    /** Packet-path handler (result item): the JEI hook. */
    public static void handleStartByResult(EntityPlayerMP player, ItemStack result) {
        if (player.isSpectator() || result.isEmpty()) {
            return;
        }
        report(player, tryStartByResult(player, result), result.getDisplayName());
    }

    private static void report(EntityPlayerMP player, StartOutcome outcome, String targetName) {
        ITextComponent feedback;
        if (outcome instanceof StartOutcome.Started) {
            CraftJob job = ((StartOutcome.Started) outcome).job();
            feedback = new TextComponentTranslation("sprawlcrafting.craft.started",
                    job.targetResult().getDisplayName(), job.totalCrafts());
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
            body = new TextComponentTranslation("sprawlcrafting.craft.unsolvable", targetName, missingNames(missing));
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
