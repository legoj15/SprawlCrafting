package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.util.ResourceLocation;

/**
 * Capability marker channel, never carries a message (same idea as {@link ProgressStatesV2}).
 * Presence of {@link #ID} on the remote side says one thing, client asks about the server
 * (issue #49): it plans and crafts with an open station's <em>protected</em> stacks — the fourth
 * material source, after plain inventory, plain station and protected inventory. A server without
 * it would plan a protected start from the plain and inventory-protected pools only and refuse it,
 * so the client counts a station's protected stacks only while this channel is present.
 *
 * <p>On Forge the main channel's presence cannot answer that (a mod-1.0.x peer has it), so this is a
 * separate channel whose version predicates, like the main one's, accept an absent or vanilla peer.
 */
public final class StationProtected {

    public static final ResourceLocation ID = Constants.id("station_protected");

    private StationProtected() {
    }
}
