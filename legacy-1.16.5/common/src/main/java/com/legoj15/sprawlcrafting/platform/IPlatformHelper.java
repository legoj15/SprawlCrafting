package com.legoj15.sprawlcrafting.platform;

import java.nio.file.Path;

import com.legoj15.sprawlcrafting.network.CraftProgressPacket;

import net.minecraft.entity.player.ServerPlayerEntity;

/**
 * The loader-specific seam for the shared 1.16.5 code. One implementation per loader
 * ({@code ForgePlatformHelper} / {@code FabricPlatformHelper}), resolved by {@link Services}.
 *
 * <p>Networking invariant (DESIGN.md "Multiplayer and compatibility"): the mod never gates server
 * access on its own presence. Channels are optional on both sides, every S2C send is guarded by
 * {@link #canReceive}, and the client only sends C2S packets when {@link #serverHasMod()} says the
 * server negotiated the channel.
 */
public interface IPlatformHelper {

    /** The loader's config directory (e.g. {@code .minecraft/config}). */
    Path getConfigDir();

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** Whether {@code player}'s client has this mod's channel (so it can receive our packets). */
    boolean canReceive(ServerPlayerEntity player);

    /** S2C progress snapshot. Implementations no-op when {@link #canReceive} is false. */
    void sendProgress(ServerPlayerEntity player, CraftProgressPacket packet);

    /**
     * Whether {@code player}'s client has the progress-states-v2 marker channel
     * ({@link com.legoj15.sprawlcrafting.network.ProgressStatesV2}), so it can decode the progress states
     * appended after the original five ({@code PAUSED_STATION}). Channel presence alone is not enough: a
     * mod-1.0.x client has the main channel but would reject the new ordinal.
     */
    boolean canReceiveProgressStatesV2(ServerPlayerEntity player);

    /**
     * Squared block-interaction distance used by the server's own use-item validation on this
     * loader: Forge honours its {@code REACH_DISTANCE} attribute ({@code (reach + 3)^2}); Fabric
     * 1.16.5 uses vanilla's fixed {@code 64.0}. Measured from the player's position to the block centre.
     */
    double blockInteractionDistanceSqr(ServerPlayerEntity player);

    // --- client-only ----------------------------------------------------------------------
    // These touch client networking; call them only from client code.

    /** Whether the connected server negotiated this mod's channel (false on vanilla/modless servers). */
    boolean serverHasMod();

    /**
     * Whether the connected server has the progress-states-v2 marker channel, i.e. it is a SprawlCrafting
     * build that plans and crafts with an open crafting station's storage. Without it the client must not
     * count a station's items (it would offer crafts the server then refuses).
     */
    boolean serverSupportsStations();

    /** C2S: confirm a deferred craft of {@code recipeId}. No-op unless {@link #serverHasMod()}. */
    void sendStartCraft(net.minecraft.util.ResourceLocation recipeId);

    /** C2S: the dimensions of the crafting grid on screen (0×0 = none). No-op unless {@link #serverHasMod()}. */
    void sendScreenState(int gridWidth, int gridHeight);

    /**
     * Whether the connected server has the cancel-craft channel, i.e. a SprawlCrafting build that can
     * cancel a running deferred craft. Without it the keybind must send nothing — an older server
     * would ignore the request, so the job would run on while the player believes it is cancelled.
     */
    boolean canCancelCraft();

    /** C2S: cancel the player's running or paused deferred craft. No-op unless {@link #canCancelCraft()}. */
    void sendCancelCraft();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
