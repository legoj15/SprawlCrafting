package com.legoj15.sprawlcrafting.platform.services;

import java.nio.file.Path;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public interface IPlatformHelper {

    /**
     * The loader's config directory (e.g. {@code .minecraft/config}). Used by
     * {@code SprawlConfig} to locate {@code sprawlcrafting.json}. Fabric resolves it via
     * {@code FabricLoader.getConfigDir()}, NeoForge via {@code FMLPaths.CONFIGDIR}.
     */
    Path getConfigDir();

    /**
     * Sends a custom payload from the client to the server. Client-only — call only from
     * client code. The raw vanilla send path works on NeoForge but is unreliable on Fabric,
     * so each loader routes through its own networking API here.
     */
    void sendToServer(CustomPacketPayload payload);

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Whether {@code player}'s connection has negotiated the given custom-payload channel —
     * i.e. the player has this mod and can receive the payload. Lets the server stream
     * progress to modded clients while gracefully skipping vanilla/modless ones (the mod
     * registers its payloads as optional, so modless clients are never kicked).
     */
    boolean canReceive(ServerPlayer player, ResourceLocation payloadId);

    /**
     * Client-side counterpart to {@link #canReceive}: whether the server has negotiated the given
     * C2S channel — i.e. the client can send this payload without it erroring/being dropped on a
     * vanilla or modless server. {@code connection} is the client's play connection, passed in by
     * the (client-only) caller so this method stays free of client-only types; the Fabric
     * implementation derives the connection from its own API and ignores the argument. Call only
     * from client code.
     */
    boolean canSendToServer(Connection connection, ResourceLocation payloadId);

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }
}