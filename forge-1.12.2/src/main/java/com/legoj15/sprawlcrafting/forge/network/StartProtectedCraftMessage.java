package com.legoj15.sprawlcrafting.forge.network;

import java.io.IOException;

import com.legoj15.sprawlcrafting.forge.craft.CraftRequests;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * C2S: the player confirmed a deferred craft whose plan needs the player's protected stacks
 * (enchanted and/or renamed, never damaged; issue #40) — the plain pool fell short, and the preview
 * said which special items would be used. Carries whichever identity the confirm UI had, exactly
 * like the two plain start messages it mirrors: a recipe id (the recipe book and the JEI hook that
 * recovered the recipe) or a result stack (the JEI hook that could not). The server re-plans
 * authoritatively with the protected tier enabled, so nothing else rides along.
 *
 * <p>C2S-only on CHANNEL2 at the next free discriminator, for the same reason as
 * {@link CancelCraftMessage}: the main channel's ids 0-2 and CHANNEL2's 0-4 (4 = cancel) are
 * frozen, and an unknown discriminator of a KNOWN channel is a kick — so this must only ever
 * travel upstream, and
 * only to a server that advertised {@link ServerHelloMessage#CAP_PROTECTED} (the client gates the
 * send on that bit).
 */
public class StartProtectedCraftMessage implements IMessage {

    private ResourceLocation recipeId;
    private ItemStack result = ItemStack.EMPTY;

    public StartProtectedCraftMessage() {
    }

    /** By-recipe form: the recipe book's and JEI's confirm, which know the recipe. */
    public StartProtectedCraftMessage(ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    /** By-result form: JEI's fallback confirm, which recovered only the output stack. */
    public StartProtectedCraftMessage(ItemStack result) {
        this.result = result;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        packet.writeBoolean(recipeId != null);
        if (recipeId != null) {
            packet.writeString(recipeId.toString());
        }
        packet.writeBoolean(!result.isEmpty());
        if (!result.isEmpty()) {
            packet.writeItemStack(result);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer packet = new PacketBuffer(buf);
        try {
            if (packet.readBoolean()) {
                recipeId = new ResourceLocation(packet.readString(256));
            }
            if (packet.readBoolean()) {
                result = packet.readItemStack();
            }
        } catch (IOException e) {
            // A truncated or hostile payload: readItemStack's failure mode. Drop the result half;
            // a recipe id already read stays usable, an empty message is a no-op server-side.
            result = ItemStack.EMPTY;
        }
    }

    public static final class Handler implements IMessageHandler<StartProtectedCraftMessage, IMessage> {
        @Override
        public IMessage onMessage(final StartProtectedCraftMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (message.recipeId != null) {
                        CraftRequests.handleStartByRecipe(player, message.recipeId, true);
                    } else if (!message.result.isEmpty()) {
                        CraftRequests.handleStartByResult(player, message.result, true);
                    }
                }
            });
            return null;
        }
    }
}
