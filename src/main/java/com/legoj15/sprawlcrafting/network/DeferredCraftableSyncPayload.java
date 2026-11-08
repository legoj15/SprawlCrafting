package com.legoj15.sprawlcrafting.network;

import java.util.HashSet;
import java.util.Set;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.resources.ResourceLocation;
//? if >=1.20.5 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;*/
//?}

/**
 * S2C (26.x only): the server-computed set of recipes craftable only via deferral, for the
 * player's current grid + inventory. On 26.x the client cannot read recipes, so it cannot run
 * the planner; the server (which has the full RecipeManager) classifies every crafting recipe
 * and pushes the deferred-only subset here. The client stores it and the recipe-book mixins /
 * JEI transfer handler read it instead of solving.
 *
 * <p>Two parallel keys because the two client UIs identify recipes differently: the recipe book
 * works in opaque {@code RecipeDisplayId} ints, while JEI hands the transfer handler a
 * {@code RecipeHolder} (a recipe {@link ResourceLocation}). Both are membership-tested.
 *
 * @param grid        0 = 2×2 inventory grid, 1 = 3×3 crafting-table grid (matches GridContext.ordinal)
 * @param displayIds  deferred-craftable RecipeDisplayId indices (recipe-book key)
 * @param recipeIds   deferred-craftable recipe identifiers (JEI key)
 */
public record DeferredCraftableSyncPayload(int grid, Set<Integer> displayIds, Set<ResourceLocation> recipeIds)
        implements SprawlPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "deferred_sync");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<DeferredCraftableSyncPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, DeferredCraftableSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DeferredCraftableSyncPayload::grid,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.collection(HashSet::new)),
                    DeferredCraftableSyncPayload::displayIds,
                    ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.collection(HashSet::new)),
                    DeferredCraftableSyncPayload::recipeIds,
                    DeferredCraftableSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<DeferredCraftableSyncPayload> type() {
        return TYPE;
    }
    //?} else {
    /*@Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(grid);
        buf.writeCollection(displayIds, FriendlyByteBuf::writeVarInt);
        buf.writeCollection(recipeIds, FriendlyByteBuf::writeResourceLocation);
    }

    public static DeferredCraftableSyncPayload read(FriendlyByteBuf buf) {
        int grid = buf.readVarInt();
        Set<Integer> displayIds = buf.readCollection(HashSet::new, FriendlyByteBuf::readVarInt);
        Set<ResourceLocation> recipeIds = buf.readCollection(HashSet::new, FriendlyByteBuf::readResourceLocation);
        return new DeferredCraftableSyncPayload(grid, displayIds, recipeIds);
    }*/
    //?}
}
