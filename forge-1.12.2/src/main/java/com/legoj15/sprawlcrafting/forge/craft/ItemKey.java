package com.legoj15.sprawlcrafting.forge.craft;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Item identity used as the solver's {@code K} type on 1.12.2.
 *
 * <p>Unlike the modern loaders, which key planning by {@link Item} alone, 1.12.2 packs many
 * distinct craftables into a single {@link Item} via the legacy <em>metadata</em> (damage)
 * value: all six plank colours are {@code minecraft:planks} with metadata 0..5, every wool
 * colour is one {@code minecraft:wool}, and so on. Keying by {@code (Item, metadata)} keeps
 * those variants distinct, so a recipe that consumes oak planks is never satisfied by birch.
 *
 * <p>NBT is deliberately ignored at plan time (the modern tree makes the same simplification
 * one tier coarser): {@code CraftExecutor} re-checks each consumed stack with
 * {@link net.minecraft.item.crafting.Ingredient#apply} and fails gracefully on divergence.
 * The wildcard metadata {@code 32767} never appears here — {@code Ingredient} explodes a
 * wildcard ingredient into its concrete sub-items before we ever see it.
 */
public final class ItemKey {

    private final Item item;
    private final int meta;

    public ItemKey(Item item, int meta) {
        this.item = item;
        this.meta = meta;
    }

    /** Key for a concrete stack. Metadata is taken verbatim (callers exclude damaged stacks). */
    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack.getItem(), stack.getMetadata());
    }

    public Item item() {
        return item;
    }

    public int meta() {
        return meta;
    }

    /** A single-item display/seed stack for this identity. */
    public ItemStack toStack() {
        return new ItemStack(item, 1, meta);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemKey)) {
            return false;
        }
        ItemKey other = (ItemKey) o;
        return meta == other.meta && item == other.item;
    }

    @Override
    public int hashCode() {
        // Item singletons hash by their stable registry id, so plans are reproducible run to run.
        return 31 * Item.getIdFromItem(item) + meta;
    }

    @Override
    public String toString() {
        ResourceLocation name = item.getRegistryName();
        String base = name == null ? String.valueOf(Item.getIdFromItem(item)) : name.toString();
        return meta == 0 ? base : base + "#" + meta;
    }
}
