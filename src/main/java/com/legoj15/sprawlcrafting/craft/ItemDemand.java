package com.legoj15.sprawlcrafting.craft;

import java.util.List;

import com.legoj15.solver.CountFormat;

import net.minecraft.resources.ResourceLocation;
//? if >=1.20.5 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * One line of a "gather list": the set of items that satisfy a missing slot (e.g. coal-or-charcoal,
 * any log) and how many the player still needs. {@code items} is the full ordered alternative set so
 * the UI can cycle through them like JEI / the recipe book; {@code items.get(0)} is the representative.
 *
 * <p>{@code count} is a {@code long} because the batched solver's gather lists legitimately run past
 * {@link Integer#MAX_VALUE} (compressed-block chains); it saturates at {@link Long#MAX_VALUE}, which
 * means "at least this many" ({@link CountFormat#isSaturated}).
 *
 * <p>Carried as {@link ResourceLocation}s + count rather than {@code Item}s/{@code ItemStack}s on
 * purpose — the codec is then registry-free, so it round-trips over a plain {@code ByteBuf} and is
 * unit-testable without bootstrapping a registry. The 26.x client resolves the ids back to items for
 * the icons (it has the item registry; only recipe data is server-only).
 *
 * <p>Two wire forms: {@code STREAM_CODEC} is the original mod-1.0.3 layout (VarInt count, clamped to
 * {@code Integer.MAX_VALUE} on encode) used by {@code ShortfallPayload}; {@code LONG_STREAM_CODEC}
 * (VarLong count) is used by {@code ShortfallV2Payload}. Never change the first one's layout: a 1.0.3
 * peer decodes it.
 */
public record ItemDemand(List<ResourceLocation> items, long count) {

    //? if >=1.20.5 {
    /** Mod-1.0.3 wire form: VarInt count, clamped on encode. Frozen. */
    public static final StreamCodec<ByteBuf, ItemDemand> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), ItemDemand::items,
            ByteBufCodecs.VAR_INT.map(Integer::longValue, CountFormat::clampToInt), ItemDemand::count,
            ItemDemand::new);

    /** V2 wire form: full VarLong count (a saturated count arrives as {@link Long#MAX_VALUE}). */
    public static final StreamCodec<ByteBuf, ItemDemand> LONG_STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), ItemDemand::items,
            ByteBufCodecs.VAR_LONG, ItemDemand::count,
            ItemDemand::new);
    //?} else {
    /*// Pre-1.20.5 wire forms (same field order as the 1.20.5+ codecs), used by the shortfall payloads'
    // write/read. write/read is the frozen 1.0.3 VarInt layout; writeLong/readLong is the V2 VarLong one.
    public void write(FriendlyByteBuf buf) {
        buf.writeCollection(items, FriendlyByteBuf::writeResourceLocation);
        buf.writeVarInt(CountFormat.clampToInt(count));
    }

    public static ItemDemand read(FriendlyByteBuf buf) {
        return new ItemDemand(buf.readList(FriendlyByteBuf::readResourceLocation), buf.readVarInt());
    }

    public void writeLong(FriendlyByteBuf buf) {
        buf.writeCollection(items, FriendlyByteBuf::writeResourceLocation);
        buf.writeVarLong(count);
    }

    public static ItemDemand readLong(FriendlyByteBuf buf) {
        return new ItemDemand(buf.readList(FriendlyByteBuf::readResourceLocation), buf.readVarLong());
    }*/
    //?}
}
