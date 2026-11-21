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
 * capability byte (see {@link #CAP_NETWORK_TERMINALS}, {@link #CAP_CANCEL_CRAFT},
 * {@link #CAP_PROTECTED} and {@link #CAP_NETWORK_PROTECTED}; absent from a pre-capability server,
 * which reads as no capabilities). Sent once at login so the client knows it may use
 * server-backed paths
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
     * Capability bit: the server registers the cancel-craft message (issue #31). A client sends
     * that id only after seeing this bit: an older v2 server knows CHANNEL2 without id 4, and FML
     * kicks the connection on an unknown discriminator of a KNOWN channel — so capability, not
     * channel presence, is the gate.
     */
    public static final int CAP_CANCEL_CRAFT = 2;

    /**
     * Capability bit: the server registers the protected-craft start (issue #40), i.e. it can plan
     * with the player's protected stacks (enchanted and/or renamed, never damaged) after a
     * confirmed request. A client offers such crafts only after seeing this bit: an older v2
     * server knows CHANNEL2 without id 5, and an unknown discriminator of a KNOWN channel is a
     * kick — the same gate the cancel uses.
     */
    public static final int CAP_PROTECTED = 4;

    /**
     * Capability bit: the server's engine also counts an open terminal network's protected stacks
     * in the protected tier (issue #49) — it plans with them and its executor may pick them. A
     * client counts the network's protected stacks only after seeing this bit: a matching-but-older
     * server never plans or picks them, so without the gate the client would offer protected
     * crafts such a server refuses to start. No message id rides on it (the protected start of
     * {@link #CAP_PROTECTED} carries those requests), and it is set exactly when the network bit
     * is: both need an AE2 provider registered.
     */
    public static final int CAP_NETWORK_PROTECTED = 8;

    /**
     * Optional trailing capability byte. Backward compatible both ways, verified against FML's
     * decoder ({@code FMLIndexedMessageToMessageCodec.decode} hands {@code fromBytes} a slice and
     * never checks for unread bytes): a pre-capability client ignores the extra byte, and a payload
     * from a pre-capability server is empty, which reads as "no capabilities". The server-side
     * instance advertises what this install can actually do: the network bit is set only when a
     * {@link NetworkTerminals} provider is registered (AE2 present), and the network-protected bit
     * rides with it (issue #49 needs the same provider); the cancel bit is always set (the engine
     * can always cancel a job); the protected bit is always set too (planning with the player's
     * own confirmed stacks needs nothing external). A received instance's value is
     * replaced by {@link #fromBytes}.
     */
    private int capabilities = CAP_CANCEL_CRAFT | CAP_PROTECTED
            | (NetworkTerminals.hasProvider() ? CAP_NETWORK_TERMINALS | CAP_NETWORK_PROTECTED : 0);

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
            ServerPresence.markPresent((message.capabilities & CAP_NETWORK_TERMINALS) != 0,
                    (message.capabilities & CAP_CANCEL_CRAFT) != 0,
                    (message.capabilities & CAP_PROTECTED) != 0,
                    (message.capabilities & CAP_NETWORK_PROTECTED) != 0);
            return new ClientHelloMessage();
        }
    }
}
