package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.List;

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
        PlanOutcome outcome = CraftPlanner.plan(player, recipe, GridContext.current(player));
        if (outcome.isPlanned()) {
            CraftJob job = outcome.job();
            CraftQueueManager.start(player, job);
            notify(player, new TranslationTextComponent("sprawlcrafting.craft.started",
                    job.targetResult().getHoverName(), job.totalCrafts()));
            return;
        }
        notify(player, describeRejection(recipe, outcome));
    }

    private static void notify(ServerPlayerEntity player, ITextComponent message) {
        player.displayClientMessage(message, false);
    }

    public static ITextComponent describeRejection(IRecipe<?> recipe, PlanOutcome outcome) {
        ITextComponent recipeName = new StringTextComponent(recipe.getId().toString());
        IFormattableTextComponent text;
        switch (outcome.kind()) {
            case NEEDS_BIGGER_GRID:
                text = new TranslationTextComponent("sprawlcrafting.craft.needs_table", recipeName);
                break;
            case TOO_COMPLEX:
                text = new TranslationTextComponent("sprawlcrafting.craft.too_complex", recipeName);
                break;
            case UNSOLVABLE: {
                List<String> names = new ArrayList<>();
                for (Item item : outcome.missing()) {
                    names.add(new TranslationTextComponent(item.getDescriptionId()).getString());
                }
                text = new TranslationTextComponent("sprawlcrafting.craft.unsolvable", recipeName,
                        new StringTextComponent(names.isEmpty() ? "?" : String.join(", ", names)));
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
