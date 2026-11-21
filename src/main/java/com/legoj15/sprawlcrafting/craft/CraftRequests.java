package com.legoj15.sprawlcrafting.craft;

import java.util.Map;
import java.util.stream.Collectors;

import com.legoj15.sprawlcrafting.craft.CraftPlanner.PlanOutcome;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameRules;

/**
 * The single server-side entry point for starting a deferred craft, shared by the
 * {@code /sprawlcrafting craft} command and the recipe book's start packet. Plans with
 * the player's current grid context and enforces the single-job rule.
 */
public final class CraftRequests {

    private CraftRequests() {
    }

    public sealed interface StartOutcome {
        record Started(CraftJob job) implements StartOutcome {}

        record Busy() implements StartOutcome {}

        record Rejected(PlanOutcome outcome) implements StartOutcome {}
    }

    /**
     * Plans and starts a job for {@code holder}, with the protected tier (issue #40):
     * {@code allowProtected} is the confirmed request from the protected channel, whose plan may also
     * count the player's protected stacks (enchanted and/or renamed, never damaged) once the plain
     * pool — inventory, then an open station's storage — has fallen short. A request that still fails
     * gets the normal rejection: the message says what is missing, not which tier ran out.
     */
    public static StartOutcome tryStart(ServerPlayer player, RecipeHolder<?> holder, boolean allowProtected) {
        if (CraftQueueManager.activeJob(player.getUUID()).isPresent()) {
            return new StartOutcome.Busy();
        }
        // Vanilla parity: clicking a recipe returns the grid's contents to the inventory before
        // acting. We intercept the click, so replicate it here — and so the planner (which reads
        // only the 36 main slots) can see items that were sitting in the grid.
        CraftExecutor.clearOpenCraftingGrid(player);
        GridContext grid = GridContext.current(player);
        // The inventory alone first: a job it can cover never depends on a crafting station, so closing the
        // station (or the network losing power) later must not pause it. Only when the inventory falls
        // short does an open, usable station (AE2 Crafting Terminal) add its storage to the pool, the
        // screen shows it so its items are craft material (issue #2); a plan made that way is
        // station-dependent. The station's counts are a storage scan, so they are taken only then.
        PlanOutcome outcome = CraftPlanner.plan(player, holder, grid);
        boolean stationDependent = false;
        StationStorage station = null;
        Map<Item, Long> stationCounts = null;
        if (outcome instanceof PlanOutcome.Unsolvable || outcome instanceof PlanOutcome.TooComplex) {
            station = StationStorages.open(player);
            if (station != null) {
                // Even when still unsolvable, report this outcome: its shortfall already counts the network.
                stationCounts = station.counts();
                outcome = CraftPlanner.plan(player, holder, grid, stationCounts);
                stationDependent = outcome instanceof PlanOutcome.Planned;
            }
        }
        if ((outcome instanceof PlanOutcome.Unsolvable || outcome instanceof PlanOutcome.TooComplex)
                && allowProtected) {
            // The confirmed fallback (#40): count the protected stacks of the 36 main slots too, in the
            // same order as the plain passes — the inventory alone first, so a job it covers never
            // depends on the station, then with an open station's counts, which makes the job
            // station-dependent (it pauses, not cancels, when the station goes away). The station's own
            // protected stacks (#49) join that second pass; like counts(), their scan is taken only
            // when a pass actually needs it, and the tier is live at all only while some protected
            // stack exists to count (the player's or the station's).
            boolean inventoryProtected = CraftPlanner.hasProtected(player.getInventory());
            Map<Item, Long> stationProtected = inventoryProtected
                    ? Map.of() : (station == null ? Map.of() : station.protectedCounts());
            if (inventoryProtected || !stationProtected.isEmpty()) {
                PlanOutcome withProtected = inventoryProtected
                        ? CraftPlanner.plan(player, holder, grid, Map.<Item, Long>of(), Map.of(), true)
                        : null;
                stationDependent = false;
                if (!(withProtected instanceof PlanOutcome.Planned) && station != null) {
                    if (stationProtected.isEmpty()) {
                        stationProtected = station.protectedCounts();
                    }
                    withProtected = CraftPlanner.plan(player, holder, grid, stationCounts, stationProtected, true);
                    stationDependent = withProtected instanceof PlanOutcome.Planned;
                }
                if (withProtected instanceof PlanOutcome.Planned planned) {
                    planned.job().setUsesProtected(true);
                    outcome = withProtected;
                }
            }
        }
        if (outcome instanceof PlanOutcome.Planned planned) {
            planned.job().setStationDependent(stationDependent);
            CraftQueueManager.start(player, planned.job());
            return new StartOutcome.Started(planned.job());
        }
        return new StartOutcome.Rejected(outcome);
    }

    /**
     * Packet-path handler: resolves the recipe, applies vanilla-parity guards
     * (spectator, doLimitedCrafting), starts the job, and reports via chat/action bar.
     * {@code allowProtected} routes to the confirmed protected tier (issue #40; see
     * {@link #tryStart(ServerPlayer, RecipeHolder, boolean)}) — the protected start packet's handler.
     */
    public static void handleStartRequest(ServerPlayer player, ResourceLocation recipeId, boolean allowProtected) {
        if (player.isSpectator()) {
            return;
        }
        RecipeHolder<?> holder = RecipeIds.byId(player.level().getServer().getRecipeManager(), recipeId).orElse(null);
        if (holder == null) {
            notify(player, Component.translatable("sprawlcrafting.craft.unknown_recipe",
                    Component.literal(recipeId.toString())).withStyle(ChatFormatting.RED));
            return;
        }
        //? if >=1.21.11 {
        /*if (player.level().getGameRules().get(GameRules.LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(holder.id())) {
            return;
        }*/
        //?} else if >=1.20.2 {
        if (player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(holder)) {
            return;
        }
        //?} else {
        /*// 1.20.1 has no RecipeHolder (the shim's id is the recipe id); its RecipeBook keys on ids.
        if (player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(holder.id())) {
            return;
        }*/
        //?}
        // instanceof chain rather than a pattern-matching switch: the 1.20.1 node targets Java 17.
        StartOutcome outcome = tryStart(player, holder, allowProtected);
        Component feedback;
        if (outcome instanceof StartOutcome.Started started) {
            feedback = Component.translatable("sprawlcrafting.craft.started",
                    started.job().targetResult().getHoverName(),
                    com.legoj15.solver.CountFormat.format(started.job().totalCrafts()));
        } else if (outcome instanceof StartOutcome.Rejected rejected) {
            feedback = describeRejection(holder, rejected.outcome());
        } else {
            feedback = Component.translatable("sprawlcrafting.craft.busy").withStyle(ChatFormatting.RED);
        }
        notify(player, feedback);
    }

    /** {@link #handleStartRequest(ServerPlayer, ResourceLocation, boolean)} for the plain start. */
    public static void handleStartRequest(ServerPlayer player, ResourceLocation recipeId) {
        handleStartRequest(player, recipeId, false);
    }

    /** Player feedback line. 26.x renamed displayClientMessage -> sendSystemMessage(overlay). */
    private static void notify(ServerPlayer player, Component message) {
        //? if >=1.21.11 {
        /*player.sendSystemMessage(message, false);*/
        //?} else {
        player.displayClientMessage(message, false);
        //?}
    }

    public static Component describeRejection(RecipeHolder<?> holder, PlanOutcome outcome) {
        Component recipeName = Component.literal(holder.id().toString());
        // instanceof chain rather than a pattern-matching switch: the 1.20.1 node targets Java 17.
        net.minecraft.network.chat.MutableComponent message;
        if (outcome instanceof PlanOutcome.Unsupported) {
            message = Component.translatable("sprawlcrafting.craft.unsupported", recipeName);
        } else if (outcome instanceof PlanOutcome.NeedsBiggerGrid) {
            message = Component.translatable("sprawlcrafting.craft.needs_table", recipeName);
        } else if (outcome instanceof PlanOutcome.TooComplex tooComplex) {
            message = Component.translatable(tooComplex.tooLarge()
                    ? "sprawlcrafting.craft.too_large" : "sprawlcrafting.craft.too_complex", recipeName);
        } else if (outcome instanceof PlanOutcome.Unsolvable unsolvable) {
            if (unsolvable.missing().isEmpty()) {
                // No missing item: every pass was rejected on replay — the materials are there, but
                // no order of crafts works, and a "not enough resources" message would be false.
                message = Component.translatable("sprawlcrafting.craft.unsolvable_order", recipeName);
            } else {
                message = Component.translatable(
                        "sprawlcrafting.craft.unsolvable", recipeName,
                        Component.literal(unsolvable.missing().stream()
                                .map(item -> Component.translatable(item.getDescriptionId()).getString())
                                .collect(Collectors.joining(", "))));
            }
        } else {
            throw new IllegalArgumentException("not a rejection"); // PlanOutcome.Planned
        }
        return message.withStyle(ChatFormatting.RED);
    }
}
