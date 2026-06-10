package com.legoj15.sprawlcrafting.command;

import java.util.stream.Collectors;

import com.legoj15.sprawlcrafting.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.craft.CraftPlanner.PlanOutcome;
import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * {@code /sprawlcrafting craft <recipe>} — plan and start a deferred craft.
 * {@code /sprawlcrafting cancel} — cancel the active job (already-crafted items remain).
 * {@code /sprawlcrafting status} — show the active job's progress.
 *
 * <p>This is the v1 entry point for the crafting engine; the recipe book UI will later
 * drive the same plan/start path via a packet.
 */
public final class SprawlCraftingCommand {

    private SprawlCraftingCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sprawlcrafting")
                .then(Commands.literal("craft")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                // Only plannable crafting recipes — not smelting/smithing/special.
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        context.getSource().getServer().getRecipeManager()
                                                .getAllRecipesFor(RecipeType.CRAFTING).stream()
                                                .filter(h -> !h.value().isSpecial())
                                                .map(RecipeHolder::id)
                                                .toList(),
                                        builder))
                                .executes(SprawlCraftingCommand::craft)))
                .then(Commands.literal("cancel")
                        .executes(SprawlCraftingCommand::cancel))
                .then(Commands.literal("status")
                        .executes(SprawlCraftingCommand::status)));
    }

    private static int craft(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        RecipeHolder<?> holder = ResourceLocationArgument.getRecipe(context, "recipe");

        if (CraftQueueManager.activeJob(player.getUUID()).isPresent()) {
            context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.busy"));
            return 0;
        }
        return switch (CraftPlanner.plan(player, holder)) {
            case PlanOutcome.Planned planned -> {
                CraftQueueManager.start(player, planned.job());
                context.getSource().sendSuccess(() -> Component.translatable("sprawlcrafting.craft.started",
                        planned.job().targetResult().getHoverName(), planned.job().totalCrafts()), false);
                yield 1;
            }
            case PlanOutcome.Unsupported unsupported -> {
                context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.unsupported",
                        Component.literal(holder.id().toString())));
                yield 0;
            }
            case PlanOutcome.Unsolvable unsolvable -> {
                Component missing = unsolvable.missing().isEmpty()
                        ? Component.literal("?")
                        : Component.literal(unsolvable.missing().stream()
                                .map(item -> item.getDescription().getString())
                                .collect(Collectors.joining(", ")));
                context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.unsolvable",
                        Component.literal(holder.id().toString()), missing));
                yield 0;
            }
            case PlanOutcome.TooComplex tooComplex -> {
                context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.too_complex",
                        Component.literal(holder.id().toString())));
                yield 0;
            }
        };
    }

    private static int cancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return CraftQueueManager.cancel(player.getUUID())
                .map(job -> {
                    Component message = Component.translatable("sprawlcrafting.craft.cancelled",
                            job.targetResult().getHoverName());
                    context.getSource().sendSuccess(() -> message, false);
                    // Overwrite any lingering progress text in the action bar.
                    player.displayClientMessage(message, true);
                    return 1;
                })
                .orElseGet(() -> {
                    context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.none"));
                    return 0;
                });
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return CraftQueueManager.activeJob(player.getUUID())
                .map(job -> {
                    context.getSource().sendSuccess(() -> Component.translatable("sprawlcrafting.craft.status",
                                    job.targetResult().getHoverName(), job.craftsDone(), job.totalCrafts())
                            .withStyle(ChatFormatting.AQUA), false);
                    return 1;
                })
                .orElseGet(() -> {
                    context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.none"));
                    return 0;
                });
    }
}
