package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * One line of a "gather list": the set of items that satisfy a missing slot (e.g. coal-or-charcoal,
 * any log) and how many the player still needs. {@code items} is the full ordered alternative set so
 * the UI can cycle through them like JEI / the recipe book; {@code items.get(0)} is the representative.
 *
 * <p>Carried as {@link ResourceLocation}s + count rather than {@code Item}s/{@code ItemStack}s on
 * purpose — the codec is then registry-free, so it round-trips over a plain {@link ByteBuf} and is
 * unit-testable without bootstrapping a registry. The 26.x client resolves the ids back to items for
 * the icons (it has the item registry; only recipe data is server-only).
 */
public record ItemDemand(List<ResourceLocation> items, int count) {

    public static final StreamCodec<ByteBuf, ItemDemand> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), ItemDemand::items,
            ByteBufCodecs.VAR_INT, ItemDemand::count,
            ItemDemand::new);
}
