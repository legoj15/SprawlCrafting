package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.List;

import com.legoj15.sprawlcrafting.craft.ItemDemand;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The compact gather-list tooltip shown on a red recipe (recipe book) or the red JEI transfer button.
 * Styled like {@code CraftPreview.lines}: a gold header, gray per-item lines, a green-italic call to
 * action. The full list lives in {@link MissingIngredientsScreen}; this is just the at-a-glance summary.
 */
public final class GatherTooltip {

    /** How many item lines to show inline before collapsing the rest into "…and N more". */
    private static final int MAX_LINES = 5;

    private GatherTooltip() {
    }

    /** Header, the first few items as "count× Name", an overflow note, an approximate note, the hint. */
    public static List<Component> compact(List<ItemDemand> demands, boolean approximate) {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("sprawlcrafting.gather.header").withStyle(ChatFormatting.GOLD));
        int shown = Math.min(MAX_LINES, demands.size());
        for (int i = 0; i < shown; i++) {
            ItemDemand demand = demands.get(i);
            out.add(Component.translatable("sprawlcrafting.gather.line", demand.count(), nameOf(demand.items().get(0)))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (demands.size() > shown) {
            out.add(Component.translatable("sprawlcrafting.gather.more", demands.size() - shown)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (approximate) {
            out.add(Component.translatable("sprawlcrafting.gather.approximate").withStyle(ChatFormatting.DARK_GRAY));
        }
        out.add(Component.translatable("sprawlcrafting.gather.click")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        return out;
    }

    /** Header + click hint only — used on 26.x where the list isn't fetched until the click. */
    public static List<Component> hint() {
        return List.of(
                Component.translatable("sprawlcrafting.gather.header").withStyle(ChatFormatting.GOLD),
                Component.translatable("sprawlcrafting.gather.click")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
    }

    static Component nameOf(ResourceLocation itemId) {
        return new ItemStack(MissingIngredients.itemOf(itemId)).getHoverName();
    }
}
