package com.legoj15.sprawlcrafting.forge.network;

import java.io.IOException;

import com.legoj15.sprawlcrafting.forge.craft.CraftRequests;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: "defer-craft the item this recipe produces." Sent by the JEI "+" hook, which recovers the
 * displayed result stack but not the backing recipe object; the server picks the best grid-fitting
 * producer for that item. The count is irrelevant — only item + metadata are read.
 */
public class StartDeferredCraftByResultMessage implements IMessage {

    private ItemStack result = ItemStack.EMPTY;

    public StartDeferredCraftByResultMessage() {
    }

    public StartDeferredCraftByResultMessage(ItemStack result) {
        this.result = result;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        new PacketBuffer(buf).writeItemStack(result);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            result = new PacketBuffer(buf).readItemStack();
        } catch (IOException e) {
            result = ItemStack.EMPTY;
        }
    }

    public static final class Handler
            implements IMessageHandler<StartDeferredCraftByResultMessage, IMessage> {
        @Override
        public IMessage onMessage(final StartDeferredCraftByResultMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    CraftRequests.handleStartByResult(player, message.result);
                }
            });
            return null;
        }
    }
}
