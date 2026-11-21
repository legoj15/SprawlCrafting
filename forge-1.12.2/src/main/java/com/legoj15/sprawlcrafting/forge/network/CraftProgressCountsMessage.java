package com.legoj15.sprawlcrafting.forge.network;

import java.io.IOException;

import com.legoj15.sprawlcrafting.forge.client.ClientCraftState;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * S2C on the v2 channel: {@link CraftProgressMessage} with the real counts — done/total as longs
 * instead of the ints the frozen main channel carries, so a job bigger than
 * {@link Integer#MAX_VALUE} crafts shows its true progress rather than the proportionally scaled
 * "x / 2.15B" of the int form (issue #46). Same fields in the same order; only the two counts
 * widen, and they go as plain longs — the sync fires at most a few times a second, so varint
 * bytes would buy nothing.
 *
 * <p>Sent only to a client that advertised {@link ClientHelloMessage#CAP_PROGRESS_COUNTS} in its
 * hello: an older v2 client knows CHANNEL2 without this discriminator, and an unknown one of a
 * KNOWN channel is a kick — capability, not channel presence, is the gate (see
 * {@code SprawlNetwork}'s version-skew invariant). The {@link Handler}'s {@code onMessage} is
 * {@code @SideOnly(Side.CLIENT)} — FML strips the body on a dedicated server, so the handler
 * class is safe to load on both sides, exactly like {@link CraftProgressMessage.Handler}.
 */
public class CraftProgressCountsMessage implements IMessage {

    private CraftProgressMessage.State state = CraftProgressMessage.State.CRAFTING;
    private ItemStack target = ItemStack.EMPTY;
    private ItemStack current = ItemStack.EMPTY;
    private long done;
    private long total;

    public CraftProgressCountsMessage() {
    }

    public CraftProgressCountsMessage(CraftProgressMessage.State state, ItemStack target,
            ItemStack current, long done, long total) {
        this.state = state;
        this.target = target;
        this.current = current;
        this.done = done;
        this.total = total;
    }

    public CraftProgressMessage.State state() {
        return state;
    }

    public ItemStack target() {
        return target;
    }

    public ItemStack current() {
        return current;
    }

    public long done() {
        return done;
    }

    public long total() {
        return total;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        packet.writeByte(state.ordinal());
        packet.writeItemStack(target);
        packet.writeItemStack(current);
        packet.writeLong(done);
        packet.writeLong(total);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        int ordinal = packet.readByte() & 0xFF;
        CraftProgressMessage.State[] values = CraftProgressMessage.State.values();
        state = ordinal >= 0 && ordinal < values.length
                ? values[ordinal] : CraftProgressMessage.State.CRAFTING;
        try {
            target = packet.readItemStack();
            current = packet.readItemStack();
        } catch (IOException e) {
            target = ItemStack.EMPTY;
            current = ItemStack.EMPTY;
        }
        done = packet.readLong();
        total = packet.readLong();
    }

    /**
     * Client handler: hands the update to {@link ClientCraftState} on the client thread, through
     * the same shared update the int form uses — one sink, so the HUD behaves identically
     * whichever form the server picked for this client.
     */
    public static final class Handler implements IMessageHandler<CraftProgressCountsMessage, IMessage> {
        @Override
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(final CraftProgressCountsMessage message, MessageContext ctx) {
            com.legoj15.sprawlcrafting.forge.client.EngineWatchdog.engineResponded();
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientCraftState.update(message.state(), message.target(), message.current(),
                            message.done(), message.total());
                }
            });
            return null;
        }
    }
}
