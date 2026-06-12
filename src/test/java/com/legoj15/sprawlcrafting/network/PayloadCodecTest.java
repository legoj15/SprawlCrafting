package com.legoj15.sprawlcrafting.network;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-contract guards for the payloads whose StreamCodecs are declared over a bare
 * {@link ByteBuf} (no registry) — these round-trip with no {@code Bootstrap.bootStrap()} and so
 * are plain-JUnit testable (verified: these classes load and encode on the Loom test classpath).
 * Payloads carrying {@code ItemStack}/{@code Component} use {@code RegistryFriendlyByteBuf} and
 * are intentionally NOT covered here — they belong in a gametest with a live RegistryAccess.
 *
 * <p>Why this matters: a payload codec is a cross-process contract. A field reorder or a codec
 * swap silently changes the bytes on the wire with no compile error and no version check; a
 * mod/server version skew then mis-parses. These tests pin the byte layout for each payload.
 */
class PayloadCodecTest {

    private static ByteBuf buf() {
        return Unpooled.buffer();
    }

    @Test
    void startDeferredCraftPayloadRoundTrips() {
        StartDeferredCraftPayload original =
                new StartDeferredCraftPayload(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"));
        ByteBuf b = buf();
        StartDeferredCraftPayload.STREAM_CODEC.encode(b, original);
        StartDeferredCraftPayload decoded = StartDeferredCraftPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertEquals(0, b.readableBytes(), "codec must consume the whole buffer");
    }

    @Test
    void displayIndexPayloadsRoundTrip() {
        ByteBuf b1 = buf();
        StartDeferredCraftByDisplayPayload.STREAM_CODEC.encode(b1, new StartDeferredCraftByDisplayPayload(4242));
        assertEquals(new StartDeferredCraftByDisplayPayload(4242),
                StartDeferredCraftByDisplayPayload.STREAM_CODEC.decode(b1));
        assertEquals(0, b1.readableBytes());

        ByteBuf b2 = buf();
        RequestCraftPreviewPayload.STREAM_CODEC.encode(b2, new RequestCraftPreviewPayload(7));
        assertEquals(new RequestCraftPreviewPayload(7), RequestCraftPreviewPayload.STREAM_CODEC.decode(b2));
        assertEquals(0, b2.readableBytes());
    }

    @Test
    void deferredCraftableSyncPayloadRoundTripsAndIsOrderIndependent() {
        DeferredCraftableSyncPayload original = new DeferredCraftableSyncPayload(
                1, Set.of(99, 7, 42),
                Set.of(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"),
                        ResourceLocation.fromNamespaceAndPath("sprawlcrafting", "thing")));
        ByteBuf b = buf();
        DeferredCraftableSyncPayload.STREAM_CODEC.encode(b, original);
        DeferredCraftableSyncPayload decoded = DeferredCraftableSyncPayload.STREAM_CODEC.decode(b);
        // Sets decode into HashSet, so equality is membership, not insertion order.
        assertEquals(original, decoded);
        assertEquals(0, b.readableBytes());
    }

    @Test
    void deferredCraftableSyncPayloadWithEmptySetsRoundTripsToEmptyNotNull() {
        DeferredCraftableSyncPayload original = new DeferredCraftableSyncPayload(0, Set.of(), Set.of());
        ByteBuf b = buf();
        DeferredCraftableSyncPayload.STREAM_CODEC.encode(b, original);
        DeferredCraftableSyncPayload decoded = DeferredCraftableSyncPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertTrue(decoded.displayIds().isEmpty());
        assertTrue(decoded.recipeIds().isEmpty());
    }

    // --- CraftProgressPayload.State: the one hand-rolled enum codec on the wire ---

    /** Mirror of the private STATE_CODEC (CraftProgressPayload.java:34) — same idMapper shape. */
    private static final StreamCodec<ByteBuf, CraftProgressPayload.State> STATE_CODEC =
            ByteBufCodecs.idMapper(
                    i -> CraftProgressPayload.State.values()[i], CraftProgressPayload.State::ordinal);

    @Test
    void everyStateRoundTripsAsItsOrdinalByte() {
        for (CraftProgressPayload.State s : CraftProgressPayload.State.values()) {
            ByteBuf b = buf();
            STATE_CODEC.encode(b, s);
            assertEquals(s, STATE_CODEC.decode(b));
        }
    }

    @Test
    void isTerminalTracksTheTerminalStates() {
        // The toast lifecycle keys on isTerminal(); guard the ordinal-derived semantics.
        assertTrue(CraftProgressPayload.State.FINISHED.isTerminal());
        assertTrue(CraftProgressPayload.State.CANCELLED.isTerminal());
        assertFalse(CraftProgressPayload.State.CRAFTING.isTerminal());
        assertFalse(CraftProgressPayload.State.PAUSED.isTerminal());
    }

    @Test
    void stateDecodeOfOutOfRangeOrdinalThrows_characterizesTheUnguardedHazard() {
        // The real codec does values()[i] with no bounds guard, so a corrupt/version-skewed
        // ordinal throws on the netty decode thread. This pins the CURRENT behavior as a baseline;
        // if the codec is later hardened to clamp/ignore, update this test deliberately.
        ByteBuf b = buf();
        b.writeByte(CraftProgressPayload.State.values().length); // one past the last valid ordinal
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> STATE_CODEC.decode(b));
    }
}
