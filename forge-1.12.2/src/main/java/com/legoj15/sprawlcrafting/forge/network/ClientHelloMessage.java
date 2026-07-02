package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.craft.ClientCraftingView;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S, empty payload: the client's reply to {@link ServerHelloMessage} — "I speak the v2 channel
 * too." The server marks the player capable in {@link ClientCraftingView} and only then allows
 * v2-dependent behavior toward them (the final-step grid hand-off and its READY_IN_GRID state);
 * an older-jar client never replies (the hello was silently dropped as an unknown channel), so it
 * keeps the exact pre-v2 behavior — every craft auto-crafts into the inventory.
 */
public class ClientHelloMessage implements IMessage {

    @Override
    public void toBytes(ByteBuf buf) {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    public static final class Handler implements IMessageHandler<ClientHelloMessage, IMessage> {
        @Override
        public IMessage onMessage(ClientHelloMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientCraftingView.markCapable(player.getUniqueID());
                }
            });
            return null;
        }
    }
}
