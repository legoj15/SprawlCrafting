package com.legoj15.sprawlcrafting.command;

import com.legoj15.sprawlcrafting.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.craft.CraftRequests;
import com.legoj15.sprawlcrafting.craft.RecipeIds;
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
                        //? if >=1.21.11 {
                        /*.then(Commands.argument("recipe", net.minecraft.commands.arguments.ResourceKeyArgument.key(net.minecraft.core.registries.Registries.RECIPE))*/
                        //?} else {
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                        //?}
                                // Only plannable crafting recipes — not smelting/smithing/special.
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        java.util.stream.StreamSupport.stream(
                                                RecipeIds.craftingRecipes(context.getSource().getServer().getRecipeManager()).spliterator(), false)
                                                .filter(h -> !h.value().isSpecial())
                                                .map(RecipeIds::id)
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
        //? if >=1.21.11 {
        /*RecipeHolder<?> holder = net.minecraft.commands.arguments.ResourceKeyArgument.getRecipe(context, "recipe");*/
        //?} else {
        RecipeHolder<?> holder = ResourceLocationArgument.getRecipe(context, "recipe");
        //?}

        return switch (CraftRequests.tryStart(player, holder)) {
            case CraftRequests.StartOutcome.Started started -> {
                context.getSource().sendSuccess(() -> Component.translatable("sprawlcrafting.craft.started",
                        started.job().targetResult().getHoverName(), started.job().totalCrafts()), false);
                yield 1;
            }
            case CraftRequests.StartOutcome.Busy busy -> {
                context.getSource().sendFailure(Component.translatable("sprawlcrafting.craft.busy"));
                yield 0;
            }
            case CraftRequests.StartOutcome.Rejected rejected -> {
                context.getSource().sendFailure(CraftRequests.describeRejection(holder, rejected.outcome()));
                yield 0;
            }
        };
    }

    private static int cancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return CraftQueueManager.cancel(player.getUUID())
                .map(job -> {
                    CraftQueueManager.syncCancelled(player, job);
                    context.getSource().sendSuccess(() -> Component.translatable(
                            "sprawlcrafting.craft.cancelled", job.targetResult().getHoverName()), false);
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
