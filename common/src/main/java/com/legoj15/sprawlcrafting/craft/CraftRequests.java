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
        RecipeHolder<?> holder = player.server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            player.displayClientMessage(Component.translatable("sprawlcrafting.craft.unknown_recipe",
                    Component.literal(recipeId.toString())).withStyle(ChatFormatting.RED), false);
            return;
        }
        if (player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(holder)) {
            return;
        }
        Component feedback = switch (tryStart(player, holder)) {
            case StartOutcome.Started started -> Component.translatable("sprawlcrafting.craft.started",
                    started.job().targetResult().getHoverName(), started.job().totalCrafts());
            case StartOutcome.Busy busy -> Component.translatable("sprawlcrafting.craft.busy")
                    .withStyle(ChatFormatting.RED);
            case StartOutcome.Rejected rejected -> describeRejection(holder, rejected.outcome());
        };
        player.displayClientMessage(feedback, false);
    }

    public static Component describeRejection(RecipeHolder<?> holder, PlanOutcome outcome) {
        Component recipeName = Component.literal(holder.id().toString());
        return (switch (outcome) {
            case PlanOutcome.Unsupported unsupported ->
                    Component.translatable("sprawlcrafting.craft.unsupported", recipeName);
            case PlanOutcome.NeedsBiggerGrid needsGrid ->
                    Component.translatable("sprawlcrafting.craft.needs_table", recipeName);
            case PlanOutcome.TooComplex tooComplex ->
                    Component.translatable("sprawlcrafting.craft.too_complex", recipeName);
            case PlanOutcome.Unsolvable unsolvable -> Component.translatable(
                    "sprawlcrafting.craft.unsolvable", recipeName,
                    unsolvable.missing().isEmpty()
                            ? Component.literal("?")
                            : Component.literal(unsolvable.missing().stream()
                                    .map(item -> item.getDescription().getString())
                                    .collect(Collectors.joining(", "))));
            case PlanOutcome.Planned planned -> throw new IllegalArgumentException("not a rejection");
        }).withStyle(ChatFormatting.RED);
    }
}
