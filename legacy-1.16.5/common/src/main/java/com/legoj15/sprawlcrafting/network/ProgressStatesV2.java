package com.legoj15.sprawlcrafting.network;

import com.legoj15.sprawlcrafting.Constants;

import net.minecraft.util.ResourceLocation;

/**
 * Capability marker channel, never carries a message (same id and idea as the root tree's
 * {@code ProgressStatesV2Payload}). Presence of {@link #ID} on the remote side says two things, one per
 * direction, both introduced with crafting stations (issue #29):
 * <ul>
 *   <li>server asks about a client: it decodes the {@link CraftProgressPacket.State}s appended after the
 *       original five ({@code PAUSED_STATION}); a client without it gets {@code PAUSED} instead;</li>
 *   <li>client asks about the server: it plans and crafts with an open station's storage, so the client may
 *       count the station's items when classifying recipes.</li>
 * </ul>
 * On Forge the main channel's presence cannot answer either (a mod-1.0.x peer has it), so this is a
 * separate channel whose version predicates, like the main one's, accept an absent or vanilla peer.
 */
public final class ProgressStatesV2 {

    public static final ResourceLocation ID = Constants.id("progress_states_v2");

    private ProgressStatesV2() {
    }
}
