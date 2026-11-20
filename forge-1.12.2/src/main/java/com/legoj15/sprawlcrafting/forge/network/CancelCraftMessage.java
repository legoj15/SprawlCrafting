package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.craft.CraftQueueManager;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: "cancel my running deferred craft" (issue #31) — the cancel keybind's entire request. The
 * server knows whose job to drop from the sender and the job needs no data to identify, so the
 * payload is empty. Only ever sent to a server that advertised
 * {@link ServerHelloMessage#CAP_CANCEL_CRAFT}: the client gates on that bit (see the
 * version-skew invariant on {@code SprawlNetwork}) because an older v2 server knows this channel
 * without this discriminator, and an unknown discriminator of a KNOWN channel is a kick.
 */
public class CancelCraftMessage implements IMessage {

    @Override
    public void toBytes(ByteBuf buf) {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    public static final class Handler implements IMessageHandler<CancelCraftMessage, IMessage> {
        @Override
        public IMessage onMessage(final CancelCraftMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    CraftQueueManager.cancel(player);
                }
            });
            return null;
        }
    }
}
