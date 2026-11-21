package com.legoj15.sprawlcrafting.network;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.legoj15.sprawlcrafting.craft.ItemDemand;

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
    void startProtectedCraftPayloadRoundTrips() {
        // Same single-ResourceLocation layout as the plain start (issue #40's protected confirm);
        // pinned separately so the two ids cannot silently swap codecs.
        StartProtectedCraftPayload original =
                new StartProtectedCraftPayload(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"));
        ByteBuf b = buf();
        StartProtectedCraftPayload.STREAM_CODEC.encode(b, original);
        StartProtectedCraftPayload decoded = StartProtectedCraftPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertEquals(0, b.readableBytes(), "codec must consume the whole buffer");
    }

    @Test
    void craftingScreenStatePayloadRoundTrips() {
        CraftingScreenStatePayload original = new CraftingScreenStatePayload(3, 3);
        ByteBuf b = buf();
        CraftingScreenStatePayload.STREAM_CODEC.encode(b, original);
        CraftingScreenStatePayload decoded = CraftingScreenStatePayload.STREAM_CODEC.decode(b);
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
    void startProtectedCraftByDisplayPayloadRoundTrips() {
        // The protected counterpart of the by-display start (issue #40): one VAR_INT display index.
        ByteBuf b = buf();
        StartProtectedCraftByDisplayPayload.STREAM_CODEC.encode(b, new StartProtectedCraftByDisplayPayload(4242));
        assertEquals(new StartProtectedCraftByDisplayPayload(4242),
                StartProtectedCraftByDisplayPayload.STREAM_CODEC.decode(b));
        assertEquals(0, b.readableBytes());
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

    @Test
    void protectedCraftableSyncPayloadRoundTripsAndIsOrderIndependent() {
        // Issue #40's protected-only subset rides the same (grid, displayIds, recipeIds) layout as
        // the deferred sync above; pinned separately so a field reorder cannot pass one and fail
        // the other unnoticed.
        ProtectedCraftableSyncPayload original = new ProtectedCraftableSyncPayload(
                1, Set.of(99, 7, 42),
                Set.of(ResourceLocation.fromNamespaceAndPath("minecraft", "chest"),
                        ResourceLocation.fromNamespaceAndPath("sprawlcrafting", "thing")));
        ByteBuf b = buf();
        ProtectedCraftableSyncPayload.STREAM_CODEC.encode(b, original);
        ProtectedCraftableSyncPayload decoded = ProtectedCraftableSyncPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertEquals(0, b.readableBytes());
    }

    @Test
    void protectedCraftableSyncPayloadWithEmptySetsRoundTripsToEmptyNotNull() {
        // The clearing push (no protected-only recipes left) is empty sets, not nulls.
        ProtectedCraftableSyncPayload original = new ProtectedCraftableSyncPayload(0, Set.of(), Set.of());
        ByteBuf b = buf();
        ProtectedCraftableSyncPayload.STREAM_CODEC.encode(b, original);
        ProtectedCraftableSyncPayload decoded = ProtectedCraftableSyncPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertTrue(decoded.displayIds().isEmpty());
        assertTrue(decoded.recipeIds().isEmpty());
    }

    @Test
    void shortfallRequestPayloadsRoundTrip() {
        ByteBuf b1 = buf();
        RequestShortfallByDisplayPayload.STREAM_CODEC.encode(b1, new RequestShortfallByDisplayPayload(5, 4242));
        assertEquals(new RequestShortfallByDisplayPayload(5, 4242),
                RequestShortfallByDisplayPayload.STREAM_CODEC.decode(b1));
        assertEquals(0, b1.readableBytes());

        ByteBuf b2 = buf();
        RequestShortfallByRecipePayload original = new RequestShortfallByRecipePayload(
                9, ResourceLocation.fromNamespaceAndPath("minecraft", "piston"));
        RequestShortfallByRecipePayload.STREAM_CODEC.encode(b2, original);
        assertEquals(original, RequestShortfallByRecipePayload.STREAM_CODEC.decode(b2));
        assertEquals(0, b2.readableBytes());
    }

    @Test
    void shortfallPayloadRoundTrips() {
        ShortfallPayload original = new ShortfallPayload(
                7, ResourceLocation.fromNamespaceAndPath("minecraft", "piston"), 1, true,
                List.of(new ItemDemand(List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "iron_ingot")), 1),
                        new ItemDemand(List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "oak_log"),
                                ResourceLocation.fromNamespaceAndPath("minecraft", "spruce_log")), 12)));
        ByteBuf b = buf();
        ShortfallPayload.STREAM_CODEC.encode(b, original);
        ShortfallPayload decoded = ShortfallPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertEquals(0, b.readableBytes(), "codec must consume the whole buffer");
    }

    @Test
    void shortfallPayloadWithEmptyDemandsRoundTripsToEmptyNotNull() {
        // Empty demands is the "nothing to gather / not plannable" signal — pin that list() yields
        // an empty list, not null, so the client can branch on isEmpty() safely.
        ShortfallPayload original = new ShortfallPayload(
                0, ResourceLocation.fromNamespaceAndPath("minecraft", "air"), 0, false, List.of());
        ByteBuf b = buf();
        ShortfallPayload.STREAM_CODEC.encode(b, original);
        ShortfallPayload decoded = ShortfallPayload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertTrue(decoded.demands().isEmpty());
    }

    @Test
    void shortfallV2PayloadCarriesLongCountsAndSaturatedFlag() {
        // approximate=false / saturated=true on purpose: two adjacent BOOLs, so a swapped field order
        // compiles but fails here. 282429536481 = 9^12 is past Integer.MAX_VALUE; Long.MAX_VALUE is the
        // solver's saturation marker and must survive the VarLong encoding unchanged.
        ShortfallV2Payload original = new ShortfallV2Payload(
                11, ResourceLocation.fromNamespaceAndPath("minecraft", "netherite_sword"), 1, false, true,
                List.of(new ItemDemand(List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone")),
                                282_429_536_481L),
                        new ItemDemand(List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "dirt")),
                                Long.MAX_VALUE)));
        ByteBuf b = buf();
        ShortfallV2Payload.STREAM_CODEC.encode(b, original);
        ShortfallV2Payload decoded = ShortfallV2Payload.STREAM_CODEC.decode(b);
        assertEquals(original, decoded);
        assertFalse(decoded.approximate());
        assertTrue(decoded.saturated());
        assertEquals(0, b.readableBytes(), "codec must consume the whole buffer");
    }

    @Test
    void legacyShortfallPayloadClampsCountsAboveIntRange() {
        // The mod-1.0.3 payload keeps its VarInt count; a long count must clamp, never wrap.
        ShortfallPayload original = new ShortfallPayload(
                3, ResourceLocation.fromNamespaceAndPath("minecraft", "piston"), 1, false,
                List.of(new ItemDemand(List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone")),
                        282_429_536_481L)));
        ByteBuf b = buf();
        ShortfallPayload.STREAM_CODEC.encode(b, original);
        ShortfallPayload decoded = ShortfallPayload.STREAM_CODEC.decode(b);
        assertEquals(Integer.MAX_VALUE, decoded.demands().get(0).count());
        assertEquals(0, b.readableBytes(), "codec must consume the whole buffer");
    }

    @Test
    void craftProgressCountsTailCarriesOverIntCounts() {
        // The counts payload (#46) exists to carry done/total past Integer.MAX_VALUE as VarLongs;
        // its ItemStack head needs a bootstrapped registry (see the class javadoc), so the round-trip
        // covers the registry-free Counts tail that the payload's own codec composes. 282429536481
        // (9^12) and Long.MAX_VALUE (a saturated job total) both overflow the int range, so a VarInt
        // swap fails here — by throwing on encode or decoding the wrong values.
        CraftProgressCountsPayload.Counts original = new CraftProgressCountsPayload.Counts(
                282_429_536_481L, Long.MAX_VALUE);
        ByteBuf b = buf();
        CraftProgressCountsPayload.Counts.STREAM_CODEC.encode(b, original);
        assertEquals(original, CraftProgressCountsPayload.Counts.STREAM_CODEC.decode(b));
        assertEquals(0, b.readableBytes(), "codec must consume the whole buffer");
    }

    // --- CraftProgressPayload.State: the one hand-rolled enum codec on the wire ---

    /**
     * Mirror of CraftProgressPayload.STATE_CODEC — same idMapper shape. A mirror, not the field: reading
     * the payload's static initializes the class, whose ItemStack codecs need a bootstrapped registry.
     */
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
        // The grid hand-off frees the job slot, so its toast lingers then slides out like FINISHED.
        assertTrue(CraftProgressPayload.State.READY_IN_GRID.isTerminal());
        assertFalse(CraftProgressPayload.State.CRAFTING.isTerminal());
        assertFalse(CraftProgressPayload.State.PAUSED.isTerminal());
        assertFalse(CraftProgressPayload.State.PAUSED_STATION.isTerminal());
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
