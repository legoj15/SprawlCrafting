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
 * S2C (26.x only): the subset of the deferred sync's recipes ({@link DeferredCraftableSyncPayload})
 * that are makeable ONLY by consuming the player's protected stacks — enchanted and/or renamed,
 * never damaged (issue #40) — for the same grid. The server classifies them in the same pass that
 * pushes the deferred set (it re-solves with the protected stacks counted) and sends this right
 * after it, so the client paints the same yellow outline and routes the confirm click to the
 * protected start ({@code StartProtectedCraftByDisplayPayload}). Same two parallel keys as the
 * deferred sync: opaque {@code RecipeDisplayId} ints for the recipe book, recipe
 * {@link ResourceLocation}s for JEI.
 *
 * <p>Only ever sent to a client that registered it ({@code canReceive}); a client that lacks it never
 * sees protected-only recipes as craftable, which is exactly right — it could not confirm them.
 *
 * @param grid        0 = 2×2 inventory grid, 1 = 3×3 crafting-table grid (matches GridContext.ordinal)
 * @param displayIds  protected-only RecipeDisplayId indices (recipe-book key)
 * @param recipeIds   protected-only recipe identifiers (JEI key)
 */
public record ProtectedCraftableSyncPayload(int grid, Set<Integer> displayIds, Set<ResourceLocation> recipeIds)
        implements SprawlPayload {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "protected_sync");

    //? if >=1.20.5 {
    public static final CustomPacketPayload.Type<ProtectedCraftableSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<ByteBuf, ProtectedCraftableSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ProtectedCraftableSyncPayload::grid,
                    ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.collection(HashSet::new)),
                    ProtectedCraftableSyncPayload::displayIds,
                    ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.collection(HashSet::new)),
                    ProtectedCraftableSyncPayload::recipeIds,
                    ProtectedCraftableSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<ProtectedCraftableSyncPayload> type() {
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

    public static ProtectedCraftableSyncPayload read(FriendlyByteBuf buf) {
        int grid = buf.readVarInt();
        Set<Integer> displayIds = buf.readCollection(HashSet::new, FriendlyByteBuf::readVarInt);
        Set<ResourceLocation> recipeIds = buf.readCollection(HashSet::new, FriendlyByteBuf::readResourceLocation);
        return new ProtectedCraftableSyncPayload(grid, displayIds, recipeIds);
    }*/
    //?}
}
