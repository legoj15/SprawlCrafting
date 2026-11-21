package com.legoj15.sprawlcrafting.network;

// Pre-1.20.5 counterpart of PayloadCodecTest (which needs StreamCodec and is excluded on those nodes).
// The legacy payloads hand-write write(FriendlyByteBuf) and a static read(FriendlyByteBuf); a field
// order mismatch between the two compiles fine and silently mis-parses on the wire, so each
// registry-free payload is round-tripped here. ItemStack/Component payloads need a bootstrapped
// registry and are left to the in-game boot checks, as in PayloadCodecTest.
// (No javadoc in this file: on 1.20.5+ nodes Stonecutter comments the whole body out.)

//? if <1.20.5 {
/*import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.legoj15.sprawlcrafting.craft.ItemDemand;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPayloadBufTest {

    private static <T extends SprawlPayload> T roundTrip(T original, Function<FriendlyByteBuf, T> read) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.write(buf);
        T decoded = read.apply(buf);
        assertEquals(0, buf.readableBytes(), "read must consume exactly what write produced");
        return decoded;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    @Test
    void c2sPayloadsRoundTrip() {
        StartDeferredCraftPayload start = new StartDeferredCraftPayload(id("chest"));
        assertEquals(start, roundTrip(start, StartDeferredCraftPayload::read));

        StartProtectedCraftPayload startProtected = new StartProtectedCraftPayload(id("chest"));
        assertEquals(startProtected, roundTrip(startProtected, StartProtectedCraftPayload::read));

        CraftingScreenStatePayload state = new CraftingScreenStatePayload(3, 2);
        assertEquals(state, roundTrip(state, CraftingScreenStatePayload::read));

        StartDeferredCraftByDisplayPayload byDisplay = new StartDeferredCraftByDisplayPayload(4242);
        assertEquals(byDisplay, roundTrip(byDisplay, StartDeferredCraftByDisplayPayload::read));

        RequestCraftPreviewPayload preview = new RequestCraftPreviewPayload(7);
        assertEquals(preview, roundTrip(preview, RequestCraftPreviewPayload::read));

        RequestShortfallByDisplayPayload shortByDisplay = new RequestShortfallByDisplayPayload(5, 4242);
        assertEquals(shortByDisplay, roundTrip(shortByDisplay, RequestShortfallByDisplayPayload::read));

        RequestShortfallByRecipePayload shortByRecipe = new RequestShortfallByRecipePayload(9, id("piston"));
        assertEquals(shortByRecipe, roundTrip(shortByRecipe, RequestShortfallByRecipePayload::read));
    }

    @Test
    void deferredCraftableSyncPayloadRoundTrips() {
        DeferredCraftableSyncPayload original = new DeferredCraftableSyncPayload(
                1, Set.of(99, 7, 42), Set.of(id("chest"), ResourceLocation.fromNamespaceAndPath("sprawlcrafting", "thing")));
        assertEquals(original, roundTrip(original, DeferredCraftableSyncPayload::read));

        DeferredCraftableSyncPayload empty = new DeferredCraftableSyncPayload(0, Set.of(), Set.of());
        DeferredCraftableSyncPayload decodedEmpty = roundTrip(empty, DeferredCraftableSyncPayload::read);
        assertEquals(empty, decodedEmpty);
        assertTrue(decodedEmpty.recipeIds().isEmpty());
    }

    @Test
    void shortfallPayloadRoundTrips() {
        ShortfallPayload original = new ShortfallPayload(7, id("piston"), 1, true,
                List.of(new ItemDemand(List.of(id("iron_ingot")), 1),
                        new ItemDemand(List.of(id("oak_log"), id("spruce_log")), 12)));
        assertEquals(original, roundTrip(original, ShortfallPayload::read));

        ShortfallPayload empty = new ShortfallPayload(0, id("air"), 0, false, List.of());
        ShortfallPayload decodedEmpty = roundTrip(empty, ShortfallPayload::read);
        assertEquals(empty, decodedEmpty);
        assertTrue(decodedEmpty.demands().isEmpty());

        // The 1.0.3 layout keeps its VarInt count: a long count clamps rather than wrapping.
        ShortfallPayload huge = new ShortfallPayload(3, id("piston"), 1, false,
                List.of(new ItemDemand(List.of(id("cobblestone")), 282_429_536_481L)));
        assertEquals(Integer.MAX_VALUE, roundTrip(huge, ShortfallPayload::read).demands().get(0).count());
    }

    @Test
    void shortfallV2PayloadCarriesLongCountsAndSaturatedFlag() {
        // approximate=false / saturated=true: adjacent booleans, so a swapped write/read order fails here.
        ShortfallV2Payload original = new ShortfallV2Payload(11, id("netherite_sword"), 1, false, true,
                List.of(new ItemDemand(List.of(id("cobblestone")), 282_429_536_481L),
                        new ItemDemand(List.of(id("dirt")), Long.MAX_VALUE)));
        ShortfallV2Payload decoded = roundTrip(original, ShortfallV2Payload::read);
        assertEquals(original, decoded);
        assertFalse(decoded.approximate());
        assertTrue(decoded.saturated());
    }
}*/
//?}
