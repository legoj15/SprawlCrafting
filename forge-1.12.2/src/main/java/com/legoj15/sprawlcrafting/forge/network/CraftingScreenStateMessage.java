package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.craft.ClientCraftingView;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: which crafting screen the client is looking at, as a grid size (0x0 = none). Exists for one
 * reason: the server cannot observe the 2x2 inventory screen — {@code player.openContainer} is the
 * inventory container whether or not the GUI is open, and opening it sends no vanilla packet (a
 * 3x3 table IS a server-opened container, so that case needs no signal). The final-step grid
 * hand-off consults this before laying items into the 2x2, or they would land in a grid the player
 * isn't looking at. Sent by {@code CraftingScreenWatcher} only while a deferred craft is running —
 * a modless server never starts one, so the optional channel stays silent there.
 */
public class CraftingScreenStateMessage implements IMessage {

    private int gridWidth;
    private int gridHeight;

    public CraftingScreenStateMessage() {
    }

    public CraftingScreenStateMessage(int gridWidth, int gridHeight) {
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(gridWidth);
        buf.writeByte(gridHeight);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        gridWidth = buf.readByte();
        gridHeight = buf.readByte();
    }

    public static final class Handler implements IMessageHandler<CraftingScreenStateMessage, IMessage> {
        @Override
        public IMessage onMessage(final CraftingScreenStateMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientCraftingView.update(player.getUniqueID(),
                            message.gridWidth, message.gridHeight);
                }
            });
            return null;
        }
    }
}
