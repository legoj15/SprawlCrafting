package com.legoj15.sprawlcrafting.forge.network;

import com.legoj15.sprawlcrafting.forge.SprawlCrafting;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The mod's {@link SimpleNetworkWrapper} channels and their message wiring. Discriminators are
 * fixed explicit ids so the numbering is stable regardless of registration order. All messages are
 * registered in {@link #initCommon()} — the {@code Side} parameter controls which side's
 * pipeline receives the handler, not which side sees the discriminator.
 *
 * <p><b>Version-skew invariant — never add a discriminator to a channel an older release has
 * registered.</b> FML's {@code FMLIndexedMessageToMessageCodec} hard-kicks the connection on an
 * unknown discriminator of a KNOWN channel, so a new message id on {@link #CHANNEL} would kick
 * every player still running an older jar the moment it is sent ({@code acceptableRemoteVersions
 * ="*"} deliberately lets mixed versions connect). An unknown CHANNEL, by contrast, is silently
 * dropped on both sides ({@code NetworkDispatcher.handle*SideCustomPacket} falls through to
 * vanilla's ignore path) — so new capabilities go on a NEW channel ({@link #CHANNEL2}), opened by
 * a hello handshake: the server hellos at login, a capable client hellos back, and each side uses
 * the new messages only after hearing the other end speak the channel.
 */
public final class SprawlNetwork {

    private SprawlNetwork() {
    }

    /** v1 channel (ids frozen since the first 1.12.2 release): progress + engine start. */
    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(SprawlCrafting.MOD_ID);

    /** v2 channel (this release): server-side grid placement + the hello/screen-state handshake. */
    public static final SimpleNetworkWrapper CHANNEL2 =
            NetworkRegistry.INSTANCE.newSimpleChannel(SprawlCrafting.MOD_ID + "2");

    private static final int ID_PROGRESS = 0;
    private static final int ID_START_BY_RESULT = 1;
    private static final int ID_START_BY_RECIPE = 2;

    private static final int ID2_SERVER_HELLO = 0;
    private static final int ID2_CLIENT_HELLO = 1;
    private static final int ID2_PLACE_RECIPE = 2;
    private static final int ID2_SCREEN_STATE = 3;

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
        CHANNEL2.registerMessage(ServerHelloMessage.Handler.class,
                ServerHelloMessage.class, ID2_SERVER_HELLO, Side.CLIENT);
        CHANNEL2.registerMessage(ClientHelloMessage.Handler.class,
                ClientHelloMessage.class, ID2_CLIENT_HELLO, Side.SERVER);
        CHANNEL2.registerMessage(PlaceRecipeMessage.Handler.class,
                PlaceRecipeMessage.class, ID2_PLACE_RECIPE, Side.SERVER);
        CHANNEL2.registerMessage(CraftingScreenStateMessage.Handler.class,
                CraftingScreenStateMessage.class, ID2_SCREEN_STATE, Side.SERVER);
    }

    public static void sendProgress(EntityPlayerMP player, CraftProgressMessage message) {
        CHANNEL.sendTo(message, player);
    }

    public static void startByResult(ItemStack result) {
        com.legoj15.sprawlcrafting.forge.client.EngineWatchdog.engineRequestSent();
        CHANNEL.sendToServer(new StartDeferredCraftByResultMessage(result));
    }

    public static void startByRecipe(ResourceLocation recipeId) {
        com.legoj15.sprawlcrafting.forge.client.EngineWatchdog.engineRequestSent();
        CHANNEL.sendToServer(new StartDeferredCraftMessage(recipeId));
    }

    public static void placeRecipe(ResourceLocation recipeId, int windowId, boolean maxTransfer) {
        CHANNEL2.sendToServer(new PlaceRecipeMessage(recipeId, windowId, maxTransfer));
    }

    public static void sendHello(EntityPlayerMP player) {
        CHANNEL2.sendTo(new ServerHelloMessage(), player);
    }

    public static void sendScreenState(int gridWidth, int gridHeight) {
        CHANNEL2.sendToServer(new CraftingScreenStateMessage(gridWidth, gridHeight));
    }
}
