package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.client.ServerPresence;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * S2C, empty payload, on the v2 channel: "this server speaks SprawlCrafting v2." Sent once at
 * login so the client knows it may use server-backed paths (the {@link PlaceRecipeMessage} grid
 * fill) instead of their client-only fallbacks. The mod is deliberately joinable in every mixed
 * setup ({@code acceptableRemoteVersions="*"}), so capability can never be assumed: a vanilla or
 * pre-v2 server never sends this (client keeps the fallbacks), and a vanilla or pre-v2 client
 * receiving it drops the unknown-channel payload without harm — which is precisely why this lives
 * on its own channel (see the version-skew invariant on {@code SprawlNetwork}). The handler
 * replies with {@link ClientHelloMessage} so the server learns the client is v2-capable too.
 * {@link ServerPresence} clears the flag again on disconnect.
 */
public class ServerHelloMessage implements IMessage {

    @Override
    public void toBytes(ByteBuf buf) {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    public static final class Handler implements IMessageHandler<ServerHelloMessage, IMessage> {
        @Override
        @SideOnly(Side.CLIENT)
        public IMessage onMessage(ServerHelloMessage message, MessageContext ctx) {
            ServerPresence.markPresent();
            return new ClientHelloMessage();
        }
    }
}
