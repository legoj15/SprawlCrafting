package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.craft.ServerGridPlacer;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: "place this recipe into my open crafting grid" — the JEI "+" button's server-side path,
 * the mod's analog of vanilla's {@code CPacketPlaceRecipe}. Carries the window id so a click that
 * raced a container switch is discarded server-side, and the shift state ({@code maxTransfer})
 * for craft-max placement. All validation and the actual item movement happen in
 * {@link ServerGridPlacer} against the server's authoritative state.
 */
public class PlaceRecipeMessage implements IMessage {

    private ResourceLocation recipeId;
    private int windowId;
    private boolean maxTransfer;

    public PlaceRecipeMessage() {
    }

    public PlaceRecipeMessage(ResourceLocation recipeId, int windowId, boolean maxTransfer) {
        this.recipeId = recipeId;
        this.windowId = windowId;
        this.maxTransfer = maxTransfer;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        packet.writeString(recipeId.toString());
        packet.writeVarInt(windowId);
        packet.writeBoolean(maxTransfer);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        recipeId = new ResourceLocation(packet.readString(256));
        windowId = packet.readVarInt();
        maxTransfer = packet.readBoolean();
    }

    public static final class Handler implements IMessageHandler<PlaceRecipeMessage, IMessage> {
        @Override
        public IMessage onMessage(final PlaceRecipeMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ServerGridPlacer.handlePlaceRecipe(player, message.recipeId,
                            message.windowId, message.maxTransfer);
                }
            });
            return null;
        }
    }
}
