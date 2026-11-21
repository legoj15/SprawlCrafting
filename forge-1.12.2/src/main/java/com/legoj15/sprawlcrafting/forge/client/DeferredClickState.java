package com.legoj15.sprawlcrafting.forge.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.legoj15.solver.CountFormat;
import com.legoj15.sprawlcrafting.forge.craft.CraftJob;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.CraftStep;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ItemKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.text.TextFormatting;

/**
 * Client state for the two-click deferred-craft flow: the armed recipe <em>is</em> the previewed
 * recipe. The first click on a yellow recipe arms it; a page button locks onto the armed variant
 * while it is armed ({@code GuiButtonRecipeMixin}), and the second click on that button — or on the
 * exact overlay (alternates-bubble) button for it — confirms and starts the craft. The preview
 * lives in the button's own tooltip ({@code GuiButtonRecipeMixin}); for the bubble, which vanilla
 * gives no tooltip, the per-frame {@link #markOverlayHover()}/{@link #consumeOverlayHover()} pair
 * gates {@code DeferredPreviewRenderer}. Armed state never outlives the screen: recipe-book init
 * disarms it ({@code GuiRecipeBookMixin}). A plan that consumes the player's protected stacks
 * (issue #40) shows the warning lines ({@link #protectedWarningLines}) in its preview, and its
 * confirm click — in {@code GuiRecipeBookMixin} — sends the protected start packet instead.
 *
 * <p>Client thread only, so plain private static fields suffice. The armed recipe is held by
 * identity — the same {@link IRecipe} instance the clicked button resolves — because a grouped
 * button cycles its displayed variant every 1.5 s, and a registry-name key could not tell the
 * previewed variant from its siblings.
 */
public final class DeferredClickState {

    private DeferredClickState() {
    }

    private static IRecipe armedRecipe;

    /**
     * Preview lines cached against the recipe and session they were planned from, so the tooltip is
     * re-planned only when either changes (a new session means the inventory signature moved).
     */
    private static IRecipe cachedRecipe;
    private static CraftPlanner.Session cachedSession;
    private static List<String> cachedLines;

    /**
     * The plain counts of the pool's sources beyond the 36 main slots ({@link CraftPlanner#plainExternalCounts}),
     * cached against the planning session they were counted over: the JEI tooltip recomputes its
     * warning per hover, and a terminal's network scan must not run per frame. One scan per session
     * rebuild — the cadence at which the session itself already decided the pool changed.
     */
    private static CraftPlanner.Session countedSession;
    private static Map<ItemKey, Long> externalCounts;

    private static boolean overlayHover;

    public static void arm(IRecipe recipe) {
        armedRecipe = recipe;
    }

    /** Clears the armed recipe and the preview caches with it. */
    public static void disarm() {
        armedRecipe = null;
        cachedRecipe = null;
        cachedSession = null;
        cachedLines = null;
        countedSession = null;
        externalCounts = null;
    }

    /** The previewed recipe, or null when nothing is armed. */
    public static IRecipe armed() {
        return armedRecipe;
    }

    public static boolean isArmedFor(IRecipe recipe) {
        return recipe != null && recipe == armedRecipe;
    }

    /**
     * Tooltip lines previewing the armed plan: title, the component crafts, and the confirm hint.
     * Planned lazily against the live session, because the preview must reflect the inventory at
     * the moment the player reads it — a click-time plan goes stale the instant anything moves (and
     * the server re-plans on start anyway, so the plan here is purely informational). A plan only
     * the protected tier can make (issue #40) is planned with it and gets
     * {@link #protectedWarningLines}' warning in the preview.
     */
    public static List<String> previewLines() {
        IRecipe recipe = armedRecipe;
        if (recipe == null) {
            return Collections.emptyList();
        }
        CraftPlanner.Session session = RecipeBookHighlight.session();
        if (session == null) {
            return Collections.emptyList();
        }
        if (recipe != cachedRecipe || session != cachedSession) {
            try {
                // The plain session is the cache key; the plan itself is protected-aware, so a
                // recipe only the protected tier can make still previews (with its warning).
                CraftPlanner.PlanOutcome outcome = ClientPlanCache.plan(
                        Minecraft.getMinecraft().player, session.grid(), recipe);
                cachedLines = Collections.unmodifiableList(
                        outcome instanceof CraftPlanner.PlanOutcome.Planned
                                ? buildLines(((CraftPlanner.PlanOutcome.Planned) outcome).job(),
                                        Minecraft.getMinecraft().player, session.grid())
                                : new ArrayList<String>());
            } catch (Throwable ignored) {
                // Runs on the render path; a failed preview must never break the tooltip.
                cachedLines = Collections.emptyList();
            }
            cachedRecipe = recipe;
            cachedSession = session;
        }
        // cachedLines is only ever null together with cachedRecipe/cachedSession (disarm clears all
        // three), so at this point it always holds the lines planned for exactly this pair.
        return cachedLines;
    }

    /** Set each frame the mouse is over the armed overlay (alternates-bubble) button. */
    public static void markOverlayHover() {
        overlayHover = true;
    }

    /** Reads and resets {@link #markOverlayHover()}'s flag, so a mark never leaks past its frame. */
    public static boolean consumeOverlayHover() {
        boolean hovered = overlayHover;
        overlayHover = false;
        return hovered;
    }

    private static List<String> buildLines(CraftJob plan, EntityPlayer player, GridContext grid) {
        List<String> lines = new ArrayList<String>();
        lines.add(TextFormatting.YELLOW + I18n.format("sprawlcrafting.preview.header",
                plan.targetResult().getDisplayName()));
        lines.add(TextFormatting.GRAY + I18n.format("sprawlcrafting.preview.steps",
                CountFormat.format(plan.totalCrafts())));
        List<CraftStep> steps = plan.steps();
        int shown = 0;
        for (int i = 0; i < steps.size() && shown < 6; i++) {
            CraftStep step = steps.get(i);
            IRecipe recipe = CraftingManager.getRecipe(step.recipeId());
            if (recipe == null) {
                continue;
            }
            ItemStack output = recipe.getRecipeOutput();
            String name = output.isEmpty() ? step.recipeId().toString() : output.getDisplayName();
            // long: a batched step's crafts x output count overflows int.
            long total = (long) step.crafts() * Math.max(1, output.getCount());
            lines.add(TextFormatting.DARK_GRAY + I18n.format("sprawlcrafting.preview.step",
                    CountFormat.format(total), name));
            shown++;
        }
        if (steps.size() > shown) {
            lines.add(TextFormatting.DARK_GRAY + " ...");
        }
        lines.addAll(protectedWarningLines(plan, player, grid));
        lines.add(TextFormatting.GREEN + I18n.format("sprawlcrafting.preview.confirm"));
        return lines;
    }

    /**
     * The protected-stack warning (issue #40): a gold header plus one line per protected item the
     * plan will consume, naming why it is special. Empty for a plan that uses none — including a
     * {@code usesProtected} job whose plain copies happened to cover everything, which can happen
     * when the inventory changed between the plan and this look. The plan's other sources (the open
     * grid and station chest, an open terminal's network) are credited as plain material: the
     * executor draws them before any protected copy, so without them an item those sources supply
     * anyway would be over-warned. Shared with the JEI "+" tooltip
     * ({@code DeferredCraftTransferHandler}), which recomputes these lines per hover.
     */
    public static List<String> protectedWarningLines(CraftJob plan, EntityPlayer player, GridContext grid) {
        if (!plan.usesProtected()) {
            return Collections.emptyList();
        }
        List<ItemStack> used = CraftPlanner.protectedItemsUsed(plan, player.inventory,
                externalPlainCounts(player, grid));
        if (used.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<String>();
        lines.add(TextFormatting.GOLD + I18n.format("sprawlcrafting.preview.protected_header"));
        for (ItemStack stack : used) {
            String key;
            if (stack.isItemEnchanted() && stack.hasDisplayName()) {
                key = "sprawlcrafting.preview.protected_both";
            } else if (stack.isItemEnchanted()) {
                key = "sprawlcrafting.preview.protected_enchanted";
            } else {
                key = "sprawlcrafting.preview.protected_renamed";
            }
            lines.add(TextFormatting.GOLD + I18n.format(key, stack.getDisplayName()));
        }
        return lines;
    }

    /**
     * {@link CraftPlanner#plainExternalCounts} against the session the plan came from
     * ({@code ClientPlanCache.get}), which is what the cache above keys on. The client session's
     * pool always includes an open terminal's network ({@code CraftPlanner.session}), so the counts
     * mirror that pool exactly: network included, empty when no terminal is open.
     */
    private static Map<ItemKey, Long> externalPlainCounts(EntityPlayer player, GridContext grid) {
        CraftPlanner.Session session = ClientPlanCache.get(player, grid);
        if (countedSession != session) {
            externalCounts = CraftPlanner.plainExternalCounts(player, true);
            countedSession = session;
        }
        return externalCounts;
    }
}
