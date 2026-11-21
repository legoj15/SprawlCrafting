package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.craft.ClientCraftingView;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: the client's reply to {@link ServerHelloMessage} — "I speak the v2 channel too" — now with
 * one optional trailing capability byte, grown the same way {@link ServerHelloMessage} grew its
 * own (issue #46). The server marks the player capable in {@link ClientCraftingView} and only then
 * allows v2-dependent behavior toward them (the final-step grid hand-off and its READY_IN_GRID
 * state, plus the long-count progress message for clients that set
 * {@link #CAP_PROGRESS_COUNTS}); an older-jar client never replies (the hello was silently dropped
 * as an unknown channel), so it keeps the exact pre-v2 behavior — every craft auto-crafts into the
 * inventory.
 */
public class ClientHelloMessage implements IMessage {

    /**
     * Capability bit: this client understands {@link CraftProgressCountsMessage} (issue #46), the
     * progress form carrying the real long done/total of a job bigger than
     * {@link Integer#MAX_VALUE} crafts. The server sends that id only after seeing this bit: an
     * older v2 client knows CHANNEL2 without id 6, and an unknown discriminator of a KNOWN channel
     * is a kick — capability, not channel presence, is the gate, the same rule the server's own
     * capability bits follow, just pointed downstream.
     */
    public static final int CAP_PROGRESS_COUNTS = 1;

    /**
     * Optional trailing capability byte, backward compatible both ways for the same reason the
     * server hello's is (FML's decoder hands {@code fromBytes} a slice and never checks for unread
     * bytes): a pre-capability server ignores the extra byte, and an empty payload from a
     * pre-capability client reads as "no capabilities". The client-side instance advertises what
     * this install can actually do — currently only the progress-counts bit. A received instance's
     * value is replaced by {@link #fromBytes}.
     */
    private int capabilities = CAP_PROGRESS_COUNTS;

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(capabilities);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        capabilities = buf.isReadable() ? buf.readUnsignedByte() : 0;
    }

    public static final class Handler implements IMessageHandler<ClientHelloMessage, IMessage> {
        @Override
        public IMessage onMessage(ClientHelloMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final boolean progressCounts = (message.capabilities & CAP_PROGRESS_COUNTS) != 0;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    // A logout between the hello's decode and this task already ran
                    // ClientCraftingView.clear; marking now would leave the flags on an offline UUID,
                    // and a later pre-#46 client under that UUID would be sent id 6 and kicked.
                    // hasDisconnected() flips just before the logout event, on this same thread.
                    if (player.hasDisconnected()) {
                        return;
                    }
                    ClientCraftingView.markCapable(player.getUniqueID(), progressCounts);
                }
            });
            return null;
        }
    }
}
