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
 * S2C: streams a deferred-craft job's progress to the client HUD flyout. Mirrors the modern
 * {@code CraftProgressPayload}. The {@link Handler}'s {@code onMessage} is
 * {@code @SideOnly(Side.CLIENT)} — FML strips the body on a dedicated server, so the handler
 * class is safe to load on both sides (required for discriminator registration).
 */
public class CraftProgressMessage implements IMessage {

    /**
     * Terminal states get an action-bar line; transient states only repaint the flyout.
     * {@code PAUSED} = waiting on a crafting table; {@code PAUSED_STATION} = waiting on the player to
     * (re)open the Crafting Station whose grid/chest the job depends on. Appended last so the
     * ordinals the wire format uses stay stable.
     */
    public enum State {
        CRAFTING,
        PAUSED,
        FINISHED,
        CANCELLED,
        PAUSED_STATION
    }

    private State state = State.CRAFTING;
    private ItemStack target = ItemStack.EMPTY;
    private ItemStack current = ItemStack.EMPTY;
    private int done;
    private int total;

    public CraftProgressMessage() {
    }

    public CraftProgressMessage(State state, ItemStack target, ItemStack current, int done, int total) {
        this.state = state;
        this.target = target;
        this.current = current;
        this.done = done;
        this.total = total;
    }

    public State state() {
        return state;
    }

    public ItemStack target() {
        return target;
    }

    public ItemStack current() {
        return current;
    }

    public int done() {
        return done;
    }

    public int total() {
        return total;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        packet.writeByte(state.ordinal());
        packet.writeItemStack(target);
        packet.writeItemStack(current);
        packet.writeVarInt(done);
        packet.writeVarInt(total);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        int ordinal = packet.readByte() & 0xFF;
        State[] values = State.values();
        state = ordinal >= 0 && ordinal < values.length ? values[ordinal] : State.CRAFTING;
        try {
            target = packet.readItemStack();
            current = packet.readItemStack();
        } catch (IOException e) {
            target = ItemStack.EMPTY;
            current = ItemStack.EMPTY;
        }
        done = packet.readVarInt();
        total = packet.readVarInt();
    }

    /** Client handler: hands the update to {@link ClientCraftState} on the client thread. */
    public static final class Handler implements IMessageHandler<CraftProgressMessage, IMessage> {
        @Override
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(final CraftProgressMessage message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientCraftState.update(message);
                }
            });
            return null;
        }
    }
}
