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
 * explicit ids so the client and server can register a different subset of handlers (the S2C
 * progress handler is client-only) without the numbering diverging.
 */
public final class SprawlNetwork {

    private SprawlNetwork() {
    }

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(SprawlCrafting.MOD_ID);

    private static final int ID_PROGRESS = 0;
    private static final int ID_START_BY_RESULT = 1;
    private static final int ID_START_BY_RECIPE = 2;

    /** Common preInit (both sides): the C2S server handlers. */
    public static void initCommon() {
        CHANNEL.registerMessage(StartDeferredCraftByResultMessage.Handler.class,
                StartDeferredCraftByResultMessage.class, ID_START_BY_RESULT, Side.SERVER);
        CHANNEL.registerMessage(StartDeferredCraftMessage.Handler.class,
                StartDeferredCraftMessage.class, ID_START_BY_RECIPE, Side.SERVER);
    }

    /** Client-only preInit: the S2C progress handler (this is the only place its client-only
     *  Handler class is referenced, so a dedicated server never loads it). */
    public static void initClient() {
        CHANNEL.registerMessage(CraftProgressMessage.Handler.class,
                CraftProgressMessage.class, ID_PROGRESS, Side.CLIENT);
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
