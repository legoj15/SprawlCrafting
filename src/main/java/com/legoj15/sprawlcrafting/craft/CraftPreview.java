package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.legoj15.solver.CountFormat;

import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
//? if >=1.20.5 {
import net.minecraft.core.component.DataComponents;
//?}

/**
 * Builds the deferred-craft plan preview (a header, per-intermediate-item totals, and a confirm
 * hint) from a planned {@link CraftJob}. Cross-version and side-neutral: 1.21.1 calls it on the
 * client (which has the RecipeManager), 26.x calls it on the server and streams the lines down,
 * since the 26.x client cannot resolve a step's recipe to its result. A job planned with the
 * player's protected stacks (issue #40) also gets the warning lines {@link #protectedWarningLines}
 * adds: which special items the plan will consume.
 */
public final class CraftPreview {

    private CraftPreview() {
    }

    public static List<Component> lines(CraftJob job, RecipeManager recipes, RegistryAccess registries,
                                        Inventory inventory, Map<Item, Long> station) {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("sprawlcrafting.preview.header",
                job.targetResult().getHoverName()).withStyle(ChatFormatting.YELLOW));
        // Aggregate intermediate output per item (a chain may revisit the same recipe in
        // non-adjacent steps); the final step is the target itself — the header covers it.
        // long totals: a batched step can be thousands of crafts, so count × crafts overflows int.
        // Shown as a pre-formatted string (CountFormat), which also reads the same on every client
        // when the 26.x server builds these lines.
        Map<Item, Long> totals = new LinkedHashMap<>();
        Map<Item, Component> names = new HashMap<>();
        for (CraftStep step : job.steps().subList(0, job.steps().size() - 1)) {
            ItemStack produced = RecipeIds.byId(recipes, step.recipeId())
                    .map(holder -> RecipeIds.resultOf(holder.value(), registries))
                    .orElse(ItemStack.EMPTY);
            if (!produced.isEmpty()) {
                totals.merge(produced.getItem(), produced.getCount() * step.crafts(), CraftJob::saturatingAdd);
                names.putIfAbsent(produced.getItem(), produced.getHoverName());
            }
        }
        totals.forEach((item, count) -> out.add(Component.translatable("sprawlcrafting.preview.step",
                CountFormat.format(count), names.get(item)).withStyle(ChatFormatting.GRAY)));
        out.addAll(protectedWarningLines(job, recipes, registries, inventory, station));
        out.add(Component.translatable("sprawlcrafting.preview.confirm")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        return out;
    }

    /**
     * The protected-stack warning (issue #40): a gold header plus one line per protected item the plan
     * will consume, naming why it is special. Empty for a plan that uses none — including a
     * {@code usesProtected} job whose plain copies happened to cover everything, which can happen when
     * the inventory changed between the plan and this look. {@code station} is the plain counts of the
     * other sources the plan used (an open crafting station's storage; empty for none), which the
     * executor draws before any protected copy and so count as plain here (see
     * {@link CraftPlanner#protectedItemsUsed}).
     */
    public static List<Component> protectedWarningLines(CraftJob job, RecipeManager recipes,
                                                        RegistryAccess registries, Inventory inventory,
                                                        Map<Item, Long> station) {
        if (!job.usesProtected()) {
            return Collections.emptyList();
        }
        List<ItemStack> used = CraftPlanner.protectedItemsUsed(job, recipes, registries, inventory, station);
        if (used.isEmpty()) {
            return Collections.emptyList();
        }
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("sprawlcrafting.preview.protected_header")
                .withStyle(ChatFormatting.GOLD));
        for (ItemStack stack : used) {
            String key;
            //? if >=1.20.5 {
            if (stack.isEnchanted() && stack.has(DataComponents.CUSTOM_NAME)) {
                key = "sprawlcrafting.preview.protected_both";
            } else if (stack.isEnchanted()) {
                key = "sprawlcrafting.preview.protected_enchanted";
            } else {
                key = "sprawlcrafting.preview.protected_renamed";
            }
            //?} else {
            /*// Pre-components NBT name.
            if (stack.isEnchanted() && stack.hasCustomHoverName()) {
                key = "sprawlcrafting.preview.protected_both";
            } else if (stack.isEnchanted()) {
                key = "sprawlcrafting.preview.protected_enchanted";
            } else {
                key = "sprawlcrafting.preview.protected_renamed";
            }*/
            //?}
            out.add(Component.translatable(key, stack.getHoverName()).withStyle(ChatFormatting.GOLD));
        }
        return out;
    }
}
