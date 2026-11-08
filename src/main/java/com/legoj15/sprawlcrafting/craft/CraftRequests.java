package com.legoj15.sprawlcrafting.craft;

import java.util.stream.Collectors;

import com.legoj15.sprawlcrafting.craft.CraftPlanner.PlanOutcome;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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

    public static StartOutcome tryStart(ServerPlayer player, RecipeHolder<?> holder) {
        if (CraftQueueManager.activeJob(player.getUUID()).isPresent()) {
            return new StartOutcome.Busy();
        }
        // Vanilla parity: clicking a recipe returns the grid's contents to the inventory before
        // acting. We intercept the click, so replicate it here — and so the planner (which reads
        // only the 36 main slots) can see items that were sitting in the grid.
        CraftExecutor.clearOpenCraftingGrid(player);
        PlanOutcome outcome = CraftPlanner.plan(player, holder, GridContext.current(player));
        if (outcome instanceof PlanOutcome.Planned planned) {
            CraftQueueManager.start(player, planned.job());
            return new StartOutcome.Started(planned.job());
        }
        return new StartOutcome.Rejected(outcome);
    }

    /**
     * Packet-path handler: resolves the recipe, applies vanilla-parity guards
     * (spectator, doLimitedCrafting), starts the job, and reports via chat/action bar.
     */
    public static void handleStartRequest(ServerPlayer player, ResourceLocation recipeId) {
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
        StartOutcome outcome = tryStart(player, holder);
        Component feedback;
        if (outcome instanceof StartOutcome.Started started) {
            feedback = Component.translatable("sprawlcrafting.craft.started",
                    started.job().targetResult().getHoverName(),
                    com.legoj15.sprawlcrafting.craft.solver.CountFormat.format(started.job().totalCrafts()));
        } else if (outcome instanceof StartOutcome.Rejected rejected) {
            feedback = describeRejection(holder, rejected.outcome());
        } else {
            feedback = Component.translatable("sprawlcrafting.craft.busy").withStyle(ChatFormatting.RED);
        }
        notify(player, feedback);
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
        } else if (outcome instanceof PlanOutcome.TooComplex) {
            message = Component.translatable("sprawlcrafting.craft.too_complex", recipeName);
        } else if (outcome instanceof PlanOutcome.Unsolvable unsolvable) {
            message = Component.translatable(
                    "sprawlcrafting.craft.unsolvable", recipeName,
                    unsolvable.missing().isEmpty()
                            ? Component.literal("?")
                            : Component.literal(unsolvable.missing().stream()
                                    .map(item -> Component.translatable(item.getDescriptionId()).getString())
                                    .collect(Collectors.joining(", "))));
        } else {
            throw new IllegalArgumentException("not a rejection"); // PlanOutcome.Planned
        }
        return message.withStyle(ChatFormatting.RED);
    }
}
