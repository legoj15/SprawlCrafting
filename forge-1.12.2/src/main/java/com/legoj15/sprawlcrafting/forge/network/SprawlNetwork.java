package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.SprawlCrafting;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The mod's {@link SimpleNetworkWrapper} channel and its message wiring. Discriminators are fixed
 * explicit ids so the numbering is stable regardless of registration order. All messages are
 * registered in {@link #initCommon()} — the {@code Side} parameter controls which side's
 * pipeline receives the handler, not which side sees the discriminator.
 */
public final class SprawlNetwork {

    private SprawlNetwork() {
    }

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(SprawlCrafting.MOD_ID);

    private static final int ID_PROGRESS = 0;
    private static final int ID_START_BY_RESULT = 1;
    private static final int ID_START_BY_RECIPE = 2;

    /** Common preInit (both sides): all message discriminators + their handlers. Every message
     *  must be registered here, even S2C ones, because the server needs the discriminator to encode
     *  outbound packets. The Side parameter controls which side's pipeline receives the handler; it
     *  does NOT gate discriminator registration. CraftProgressMessage.Handler is safe to class-load
     *  on a dedicated server: its onMessage body is {@code @SideOnly(Side.CLIENT)}, so FML strips
     *  it, and the handler never fires because no messages arrive through the CLIENT pipeline on a
     *  dedicated server. */
    public static void initCommon() {
        CHANNEL.registerMessage(CraftProgressMessage.Handler.class,
                CraftProgressMessage.class, ID_PROGRESS, Side.CLIENT);
        CHANNEL.registerMessage(StartDeferredCraftByResultMessage.Handler.class,
                StartDeferredCraftByResultMessage.class, ID_START_BY_RESULT, Side.SERVER);
        CHANNEL.registerMessage(StartDeferredCraftMessage.Handler.class,
                StartDeferredCraftMessage.class, ID_START_BY_RECIPE, Side.SERVER);
    }

    public static void sendProgress(EntityPlayerMP player, CraftProgressMessage message) {
        CHANNEL.sendTo(message, player);
    }

    public static void startByResult(ItemStack result) {
        CHANNEL.sendToServer(new StartDeferredCraftByResultMessage(result));
    }

    public static void startByRecipe(ResourceLocation recipeId) {
        CHANNEL.sendToServer(new StartDeferredCraftMessage(recipeId));
    }
}
