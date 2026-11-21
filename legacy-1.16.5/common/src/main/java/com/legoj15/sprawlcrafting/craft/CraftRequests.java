package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.PlanOutcome;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameRules;

/**
 * The single server-side entry point for starting a deferred craft (the recipe book's and JEI's start
 * packet). Plans with the player's current grid context and enforces the single-job rule.
 */
public final class CraftRequests {

    private CraftRequests() {
    }

    /**
     * Packet-path handler: resolves the recipe, applies vanilla-parity guards (spectator,
     * doLimitedCrafting), starts the job, and reports via chat.
     */
    public static void handleStartRequest(ServerPlayerEntity player, ResourceLocation recipeId) {
        handleStartRequest(player, recipeId, false);
    }

    /**
     * {@link #handleStartRequest(ServerPlayerEntity, ResourceLocation)} with the protected tier
     * (issue #40): {@code allowProtected} is the confirmed request from the protected channel, whose
     * plan may also count the player's protected stacks (enchanted and/or renamed, never damaged)
     * once the plain pool — inventory, then an open station's storage — has fallen short. A request
     * that still fails gets the normal rejection: the message says what is missing, not which tier
     * ran out.
     */
    public static void handleStartRequest(ServerPlayerEntity player, ResourceLocation recipeId,
                                          boolean allowProtected) {
        if (player.isSpectator()) {
            return;
        }
        IRecipe<?> recipe = RecipeIds.byId(player.getServer().getRecipeManager(), recipeId).orElse(null);
        if (recipe == null) {
            notify(player, new TranslationTextComponent("sprawlcrafting.craft.unknown_recipe",
                    new StringTextComponent(recipeId.toString())).withStyle(TextFormatting.RED));
            return;
        }
        if (player.getLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(recipe)) {
            return;
        }
        if (CraftQueueManager.activeJob(player.getUUID()).isPresent()) {
            notify(player, new TranslationTextComponent("sprawlcrafting.craft.busy").withStyle(TextFormatting.RED));
            return;
        }
        // Vanilla parity: clicking a recipe returns the grid's contents to the inventory before acting.
        // We intercept the click, so replicate it — the planner reads only the 36 main slots.
        CraftExecutor.clearOpenCraftingGrid(player);
        GridContext grid = GridContext.current(player);
        // The inventory alone first: a job it can cover never depends on a crafting station, so closing the
        // station (or the network losing power) later must not pause it. Only when the inventory falls short
        // does an open, usable station (AE2 Crafting Terminal) add its storage to the pool; a plan made that
        // way is station-dependent. The station's counts are a storage scan, so they are taken only then.
        PlanOutcome outcome = CraftPlanner.plan(player, recipe, grid);
        boolean stationDependent = false;
        StationStorage station = null;
        Map<Item, Long> stationCounts = null;
        if (outcome.kind() == PlanOutcome.Kind.UNSOLVABLE || outcome.kind() == PlanOutcome.Kind.TOO_COMPLEX) {
            station = StationStorages.open(player);
            if (station != null) {
                // Even when still unsolvable, report this outcome: its shortfall already counts the network.
                stationCounts = station.counts();
                outcome = CraftPlanner.plan(player, recipe, grid, stationCounts);
                stationDependent = outcome.isPlanned();
            }
        }
        if ((outcome.kind() == PlanOutcome.Kind.UNSOLVABLE || outcome.kind() == PlanOutcome.Kind.TOO_COMPLEX)
                && allowProtected) {
            // The confirmed fallback (#40, widened to the station's protected stacks in #49): count the
            // protected stacks too, in per-source order — plain inventory, plain station, protected
            // inventory, protected station. The inventory's protected pass first (skipped when the
            // inventory holds no protected stack), so a job it covers never depends on the station;
            // then with an open station's plain and protected counts together, which makes the job
            // station-dependent (it pauses, not cancels, when the station goes away). The station's
            // protected counts are a storage scan, so they are read once — here, on a confirmed
            // request that already failed every plain pass.
            boolean inventoryProtected = CraftPlanner.hasProtected(player.inventory);
            Map<Item, Long> stationProtected = station != null
                    ? protectedCountsOf(station) : Collections.<Item, Long>emptyMap();
            if (inventoryProtected || !stationProtected.isEmpty()) {
                PlanOutcome withProtected = inventoryProtected
                        ? CraftPlanner.plan(player, recipe, grid, Collections.<Item, Long>emptyMap(),
                                Collections.<Item, Long>emptyMap(), true)
                        : null;
                stationDependent = false;
                if ((withProtected == null || !withProtected.isPlanned()) && station != null) {
                    withProtected = CraftPlanner.plan(player, recipe, grid, stationCounts, stationProtected, true);
                    stationDependent = withProtected.isPlanned();
                }
                if (withProtected != null && withProtected.isPlanned()) {
                    withProtected.job().setUsesProtected(true);
                    outcome = withProtected;
                }
            }
        }
        if (outcome.isPlanned()) {
            CraftJob job = outcome.job();
            job.setStationDependent(stationDependent);
            CraftQueueManager.start(player, job);
            notify(player, new TranslationTextComponent("sprawlcrafting.craft.started",
                    job.targetResult().getHoverName(),
                    com.legoj15.solver.CountFormat.format(job.totalCrafts())));
            return;
        }
        notify(player, describeRejection(recipe, outcome));
    }

    private static void notify(ServerPlayerEntity player, ITextComponent message) {
        player.displayClientMessage(message, false);
    }

    /**
     * {@link StationStorage#protectedCounts()} read as "no protected stacks" when the station's mod
     * throws (issue #49): the protected fallback then simply has no station tier to try, exactly like
     * a station that holds none.
     */
    private static Map<Item, Long> protectedCountsOf(StationStorage station) {
        try {
            Map<Item, Long> counts = station.protectedCounts();
            return counts != null ? counts : Collections.<Item, Long>emptyMap();
        } catch (RuntimeException | LinkageError e) {
            Constants.LOG.warn("SprawlCrafting: crafting station protected-count read failed,"
                    + " treating it as none", e);
            return Collections.emptyMap();
        }
    }

    public static ITextComponent describeRejection(IRecipe<?> recipe, PlanOutcome outcome) {
        ITextComponent recipeName = new StringTextComponent(recipe.getId().toString());
        IFormattableTextComponent text;
        switch (outcome.kind()) {
            case NEEDS_BIGGER_GRID:
                text = new TranslationTextComponent("sprawlcrafting.craft.needs_table", recipeName);
                break;
            case TOO_COMPLEX:
                text = new TranslationTextComponent(outcome.tooLarge()
                        ? "sprawlcrafting.craft.too_large" : "sprawlcrafting.craft.too_complex", recipeName);
                break;
            case UNSOLVABLE: {
                List<String> names = new ArrayList<>();
                for (Item item : outcome.missing()) {
                    names.add(new TranslationTextComponent(item.getDescriptionId()).getString());
                }
                // No missing names: every pass was rejected on replay — the materials are there, but
                // no order of crafts works, and a "not enough resources" message would be false.
                text = names.isEmpty()
                        ? new TranslationTextComponent("sprawlcrafting.craft.unsolvable_order", recipeName)
                        : new TranslationTextComponent("sprawlcrafting.craft.unsolvable", recipeName,
                                new StringTextComponent(String.join(", ", names)));
                break;
            }
            case UNSUPPORTED:
            default:
                text = new TranslationTextComponent("sprawlcrafting.craft.unsupported", recipeName);
                break;
        }
        return text.withStyle(TextFormatting.RED);
    }
}
