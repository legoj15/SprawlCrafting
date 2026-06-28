package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the dimensions of the crafting grid the player currently has on screen, or
 * {@code 0×0} when no crafting screen is open. The server cannot otherwise observe the
 * 2×2 inventory crafting screen — {@code ServerPlayer.containerMenu} is always the
 * {@code InventoryMenu} whether or not the inventory GUI is showing, and opening it sends
 * no vanilla packet. This signal lets the server decide, when a deferred craft's final
 * step comes due, whether to hand the last craft off to an open grid for the player to
 * grab (see {@code CraftQueueManager}/{@code ClientCraftingView}).
 *
 * <p>On 1.21.1 the client only sends this while a job is active ({@code ClientJobTracker}),
 * which both gates it to SprawlCrafting servers and keeps it to a handful of packets per job.
 * On 26.x it is sent continuously (on every crafting-screen open/close), gated instead by
 * {@code IPlatformHelper.canSendToServer} so vanilla/modless servers never see it — the server
 * needs the open/closed signal to gate its deferred-craftable reclassification ({@code
 * DeferredCraftSync.maybeSync}) to players who actually have a recipe screen open. Sent only on
 * change (grid dimensions differ from the last send), so an idle player generates no traffic.
 */
public record CraftingScreenStatePayload(int gridWidth, int gridHeight) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CraftingScreenStatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "crafting_screen_state"));

    public static final StreamCodec<ByteBuf, CraftingScreenStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CraftingScreenStatePayload::gridWidth,
                    ByteBufCodecs.VAR_INT, CraftingScreenStatePayload::gridHeight,
                    CraftingScreenStatePayload::new);

    @Override
    public CustomPacketPayload.Type<CraftingScreenStatePayload> type() {
        return TYPE;
    }
}
