package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.craft.CraftRequests;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: "defer-craft this specific recipe." Used by the recipe-book click intercept (the stretch
 * UI) and available to any future caller that already knows the recipe id.
 */
public class StartDeferredCraftMessage implements IMessage {

    private ResourceLocation recipeId;

    public StartDeferredCraftMessage() {
    }

    public StartDeferredCraftMessage(ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        new PacketBuffer(buf).writeString(recipeId.toString());
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        recipeId = new ResourceLocation(new PacketBuffer(buf).readString(256));
    }

    public static final class Handler implements IMessageHandler<StartDeferredCraftMessage, IMessage> {
        @Override
        public IMessage onMessage(final StartDeferredCraftMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    CraftRequests.handleStartByRecipe(player, message.recipeId);
                }
            });
            return null;
        }
    }
}
