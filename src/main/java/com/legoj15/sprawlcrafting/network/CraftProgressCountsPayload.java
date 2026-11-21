package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
//? if >=1.20.5 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * S2C: {@link CraftProgressPayload} with the real {@code long} counts (issue #46). The legacy payload's
 * done/total are VarInts on a frozen mod-1.0.3 wire, so a job of more than {@link Integer#MAX_VALUE}
 * crafts reaches an older client scaled down ("x / 2.15B") while chat shows the true figure. This
 * payload writes the same head (state ordinal, target, current) followed by done/total as VarLongs,
 * and the server picks per client ({@code CraftQueueManager.sync}): one that registered this channel
 * gets it, any other keeps the legacy bytes untouched.
 *
 * <p>Version negotiation is the channel list itself, like {@link ProgressStatesV2Payload}'s marker but
 * with a body: {@code canReceive(player, ID)} is exactly "this client decodes long counts". The client
 * converts on receipt ({@link #toProgressPayload()}), so the tracker and the toast keep one type.
 * Registered optional on every loader; on Forge 1.20.1, whose main channel's discriminators are frozen,
 * it rides its own {@code SimpleChannel} like {@code CancelCraftPayload} (see {@code ForgeNetwork}).
 */
public record CraftProgressCountsPayload(CraftProgressPayload.State state, ItemStack target, ItemStack current,
                                         Counts counts) implements SprawlPayload {

    /**
     * The over-int-capable done/total pair. Declared as its own registry-free codec (bare
     * {@code ByteBuf}, no ItemStack) so PayloadCodecTest can pin the VarLong layout — the whole point
     * of this payload — without a bootstrapped registry.
     */
    public record Counts(long done, long total) {
        //? if >=1.20.5 {
        public static final StreamCodec<ByteBuf, Counts> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Counts::done,
                ByteBufCodecs.VAR_LONG, Counts::total,
                Counts::new);
        //?}
    }

    public CraftProgressCountsPayload(CraftProgressPayload.State state, ItemStack target, ItemStack current,
                                      long done, long total) {
        this(state, target, current, new Counts(done, total));
    }

    public long done() {
        return counts.done();
    }

    public long total() {
        return counts.total();
    }

    /** The same snapshot as the tracker's single type, carrying the real long counts (issue #46). */
    public CraftProgressPayload toProgressPayload() {
        return new CraftProgressPayload(state, target, current, counts.done(), counts.total());
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "craft_progress_counts");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<CraftProgressCountsPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    // Same head as CraftProgressPayload (shared STATE_CODEC), then the long tail. ItemStacks need the
    // registry-aware buffer on 1.20.5+, which is why this payload keeps its composite codec rather
    // than sharing the pre-1.20.5 write/read pair below.
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftProgressCountsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    CraftProgressPayload.STATE_CODEC, CraftProgressCountsPayload::state,
                    ItemStack.OPTIONAL_STREAM_CODEC, CraftProgressCountsPayload::target,
                    ItemStack.OPTIONAL_STREAM_CODEC, CraftProgressCountsPayload::current,
                    Counts.STREAM_CODEC, CraftProgressCountsPayload::counts,
                    CraftProgressCountsPayload::new);

    @Override
    public CustomPacketPayload.Type<CraftProgressCountsPayload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    // Same field order as the 1.20.5+ codec: state ordinal (VarInt), target, current, then done/total
    // as VarLongs.
    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(state.ordinal());
        buf.writeItem(target);
        buf.writeItem(current);
        buf.writeVarLong(counts.done());
        buf.writeVarLong(counts.total());
    }

    public static CraftProgressCountsPayload read(FriendlyByteBuf buf) {
        return new CraftProgressCountsPayload(CraftProgressPayload.State.values()[buf.readVarInt()],
                buf.readItem(), buf.readItem(), buf.readVarLong(), buf.readVarLong());
    }*/
    //?}
}
