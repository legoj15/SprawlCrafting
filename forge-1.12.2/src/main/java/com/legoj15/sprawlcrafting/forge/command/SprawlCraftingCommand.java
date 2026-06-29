package com.legoj15.sprawlcrafting.forge.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.legoj15.sprawlcrafting.forge.craft.CraftJob;
import com.legoj15.sprawlcrafting.forge.craft.CraftQueueManager;
import com.legoj15.sprawlcrafting.forge.craft.CraftRequests;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /sprawlcrafting craft <recipe>} — plan and start a deferred craft.
 * {@code /sprawlcrafting cancel} — cancel the active job (already-crafted items remain).
 * {@code /sprawlcrafting status} — show the active job's progress.
 *
 * <p>The Java-8 / 1.12.2 {@link CommandBase} counterpart of the modern Brigadier command. Usable by
 * all players (permission level 0) — the deferred engine only consumes the player's own inventory.
 */
public final class SprawlCraftingCommand extends CommandBase {

    @Override
    public String getName() {
        return "sprawlcrafting";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("sprawlcraft", "sc");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/sprawlcrafting <craft <recipe> | cancel | status>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("craft".equals(sub)) {
            craft(sender, player, args);
        } else if ("cancel".equals(sub)) {
            cancel(player);
        } else if ("status".equals(sub)) {
            status(player);
        } else {
            throw new WrongUsageException(getUsage(sender));
        }
    }

    private void craft(ICommandSender sender, EntityPlayerMP player, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/sprawlcrafting craft <recipe>");
        }
        ResourceLocation id = new ResourceLocation(args[1]);
        IRecipe recipe = CraftingManager.getRecipe(id);
        if (recipe == null) {
            throw new CommandException("sprawlcrafting.craft.unknown_recipe", id.toString());
        }
        CraftRequests.StartOutcome outcome = CraftRequests.tryStart(player, recipe);
        if (outcome instanceof CraftRequests.StartOutcome.Started) {
            CraftJob job = ((CraftRequests.StartOutcome.Started) outcome).job();
            sender.sendMessage(coloured(new TextComponentTranslation("sprawlcrafting.craft.started",
                    job.targetResult().getDisplayName(), job.totalCrafts()), TextFormatting.GREEN));
        } else if (outcome instanceof CraftRequests.StartOutcome.Busy) {
            sender.sendMessage(coloured(new TextComponentTranslation("sprawlcrafting.craft.busy"),
                    TextFormatting.RED));
        } else {
            sender.sendMessage(CraftRequests.describeRejection(
                    ((CraftRequests.StartOutcome.Rejected) outcome).outcome()));
        }
    }

    private void cancel(EntityPlayerMP player) {
        Optional<CraftJob> cancelled = CraftQueueManager.cancel(player.getUniqueID());
        if (cancelled.isPresent()) {
            CraftJob job = cancelled.get();
            CraftQueueManager.syncCancelled(player, job);
            player.sendMessage(new TextComponentTranslation("sprawlcrafting.craft.cancelled",
                    job.targetResult().getDisplayName()));
        } else {
            player.sendMessage(coloured(new TextComponentTranslation("sprawlcrafting.craft.none"),
                    TextFormatting.RED));
        }
    }

    private void status(EntityPlayerMP player) {
        Optional<CraftJob> active = CraftQueueManager.activeJob(player.getUniqueID());
        if (active.isPresent()) {
            CraftJob job = active.get();
            player.sendMessage(coloured(new TextComponentTranslation("sprawlcrafting.craft.status",
                    job.targetResult().getDisplayName(), job.craftsDone(), job.totalCrafts()),
                    TextFormatting.AQUA));
        } else {
            player.sendMessage(coloured(new TextComponentTranslation("sprawlcrafting.craft.none"),
                    TextFormatting.RED));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                           String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "craft", "cancel", "status");
        }
        if (args.length == 2 && "craft".equalsIgnoreCase(args[0])) {
            List<String> ids = new ArrayList<String>();
            for (IRecipe recipe : CraftingManager.REGISTRY) {
                if (!recipe.isDynamic() && recipe.getRegistryName() != null
                        && !recipe.getRecipeOutput().isEmpty()) {
                    ids.add(recipe.getRegistryName().toString());
                }
            }
            return getListOfStringsMatchingLastWord(args, ids);
        }
        return Collections.emptyList();
    }

    private static ITextComponent coloured(ITextComponent component, TextFormatting color) {
        component.getStyle().setColor(color);
        return component;
    }
}
