package com.legoj15.sprawlcrafting.client;

import java.util.ArrayList;
import java.util.List;

import com.legoj15.sprawlcrafting.craft.ItemDemand;
import com.legoj15.solver.CountFormat;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * The compact gather-list tooltip shown on the orange JEI transfer button: a gold header, gray per-item
 * lines, a green-italic call to action. The full list lives in {@link MissingIngredientsScreen}.
 */
public final class GatherTooltip {

    /** How many item lines to show inline before collapsing the rest into "…and N more". */
    private static final int MAX_LINES = 5;

    private GatherTooltip() {
    }

    /**
     * A gather count as text, in the shared {@link CountFormat} scheme ({@code 1,234}, {@code 12.3K},
     * {@code 9.22e18}); a count the solver saturated at {@link Long#MAX_VALUE} reads "more than ...".
     */
    public static ITextComponent countText(long count) {
        return CountFormat.isSaturated(count)
                ? new TranslationTextComponent("sprawlcrafting.gather.more_than", CountFormat.format(Long.MAX_VALUE))
                : new StringTextComponent(CountFormat.format(count));
    }

    /**
     * One tooltip item line: {@code 1,234× Name}, but for a saturated count {@code More than 9.22e18 Name}
     * (its own lang key: "more than 9.22e18× Name" reads as a garbled multiplier).
     */
    static IFormattableTextComponent lineText(long count, ITextComponent name) {
        return CountFormat.isSaturated(count)
                ? new TranslationTextComponent("sprawlcrafting.gather.line_more_than",
                        CountFormat.format(Long.MAX_VALUE), name)
                : new TranslationTextComponent("sprawlcrafting.gather.line", CountFormat.format(count), name);
    }

    public static List<ITextComponent> compact(List<ItemDemand> demands, boolean approximate) {
        List<ITextComponent> out = new ArrayList<>();
        out.add(new TranslationTextComponent("sprawlcrafting.gather.header").withStyle(TextFormatting.GOLD));
        int shown = Math.min(MAX_LINES, demands.size());
        for (int i = 0; i < shown; i++) {
            ItemDemand demand = demands.get(i);
            out.add(lineText(demand.count(), nameOf(demand.items().get(0))).withStyle(TextFormatting.GRAY));
        }
        if (demands.size() > shown) {
            out.add(new TranslationTextComponent("sprawlcrafting.gather.more", demands.size() - shown)
                    .withStyle(TextFormatting.DARK_GRAY));
        }
        if (approximate) {
            out.add(new TranslationTextComponent("sprawlcrafting.gather.approximate").withStyle(TextFormatting.DARK_GRAY));
        }
        out.add(new TranslationTextComponent("sprawlcrafting.gather.click")
                .withStyle(TextFormatting.GREEN, TextFormatting.ITALIC));
        return out;
    }

    static ITextComponent nameOf(ResourceLocation itemId) {
        return new ItemStack(MissingIngredients.itemOf(itemId)).getHoverName();
    }
}
