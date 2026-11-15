package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.client.ServerPresence;
import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * S2C on the v2 channel: "this server speaks SprawlCrafting v2", plus one optional trailing
 * capability byte (see {@link #CAP_NETWORK_TERMINALS}; absent from a pre-capability server, which
 * reads as no capabilities). Sent once at login so the client knows it may use server-backed paths
 * (the {@link PlaceRecipeMessage} grid fill) instead of their client-only fallbacks. The mod is
 * deliberately joinable in every mixed setup ({@code acceptableRemoteVersions="*"}), so capability
 * can never be assumed: a vanilla or
 * pre-v2 server never sends this (client keeps the fallbacks), and a vanilla or pre-v2 client
 * receiving it drops the unknown-channel payload without harm — which is precisely why this lives
 * on its own channel (see the version-skew invariant on {@code SprawlNetwork}). The handler
 * replies with {@link ClientHelloMessage} so the server learns the client is v2-capable too.
 * {@link ServerPresence} clears the flag again on disconnect.
 */
public class ServerHelloMessage implements IMessage {

    /**
     * Capability bit: the server's engine counts an open network crafting terminal's network (AE2
     * Crafting Terminal, issue #2). A client only routes terminal recipes into the engine when the
     * server sets it; otherwise the terminal's own JEI handler keeps the "+" entirely.
     */
    public static final int CAP_NETWORK_TERMINALS = 1;

    /**
     * Optional trailing capability byte. Backward compatible both ways, verified against FML's
     * decoder ({@code FMLIndexedMessageToMessageCodec.decode} hands {@code fromBytes} a slice and
     * never checks for unread bytes): a pre-capability client ignores the extra byte, and a payload
     * from a pre-capability server is empty, which reads as "no capabilities". The server-side
     * instance advertises what this install can actually do: the network bit is set only when a
     * {@link NetworkTerminals} provider is registered (AE2 present). A received instance's value is
     * replaced by {@link #fromBytes}.
     */
    private int capabilities = NetworkTerminals.hasProvider() ? CAP_NETWORK_TERMINALS : 0;

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(capabilities);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        capabilities = buf.isReadable() ? buf.readUnsignedByte() : 0;
    }

    public static final class Handler implements IMessageHandler<ServerHelloMessage, IMessage> {
        @Override
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(ServerHelloMessage message, MessageContext ctx) {
            ServerPresence.markPresent((message.capabilities & CAP_NETWORK_TERMINALS) != 0);
            return new ClientHelloMessage();
        }
    }
}
