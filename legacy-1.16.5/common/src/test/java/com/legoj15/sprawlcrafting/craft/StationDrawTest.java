package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The all-or-nothing draw from a crafting station's storage: every item a draw extracted must end up
 * either consumed by the craft (the draw completed) or given back (it did not), never held by nobody.
 * A bug here voids or duplicates the player's items, so it is pinned even though the storage behind it
 * (an AE2 network) is only reachable in game. Java 8 port of the root tree's test of the same name; the
 * class under test is a line-for-line port, so both must keep passing the same cases.
 */
class StationDrawTest {

    /** A scripted storage: each key extracts {@code stock} up to what is asked, or throws. */
    private static final class FakeStorage {
        final Map<String, Integer> stock = new LinkedHashMap<>();
        final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        final List<String> givenBack = new ArrayList<>();

        int extract(String key, int count) {
            RuntimeException failure = failures.get(key);
            if (failure != null) {
                throw failure;
            }
            return Math.min(stock.getOrDefault(key, 0), count);
        }

        void giveBack(String key, int count) {
            givenBack.add(key + "x" + count);
        }
    }

    private static Map<String, Integer> takes(Object... pairs) {
        Map<String, Integer> takes = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            takes.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return takes;
    }

    @Test
    void aCompleteDrawReturnsNullAndGivesNothingBack() {
        FakeStorage storage = new FakeStorage();
        storage.stock.put("plank", 10);
        storage.stock.put("stick", 10);

        String missing = StationDraw.draw(takes("plank", 4, "stick", 2), storage::extract, storage::giveBack);

        assertNull(missing);
        assertEquals(Collections.emptyList(), storage.givenBack);
    }

    @Test
    void aShortEntryGivesBackEverythingDrawnIncludingItsOwnPartialAndNamesIt() {
        FakeStorage storage = new FakeStorage();
        storage.stock.put("plank", 10);
        storage.stock.put("stick", 1); // asked for 2: comes up one short

        String missing = StationDraw.draw(takes("plank", 4, "stick", 2), storage::extract, storage::giveBack);

        assertEquals("stick", missing);
        assertEquals(Arrays.asList("plankx4", "stickx1"), storage.givenBack);
    }

    @Test
    void aFailingGiveBackOnTheShortPathStillGivesBackTheRestThenThrows() {
        FakeStorage storage = new FakeStorage();
        storage.stock.put("plank", 10);
        storage.stock.put("stick", 10);
        storage.stock.put("cobble", 1); // asked for 8: short
        RuntimeException insertFailure = new IllegalStateException("insert failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> StationDraw.draw(
                takes("plank", 4, "stick", 2, "cobble", 8), storage::extract, (key, count) -> {
                    if (key.equals("plank")) {
                        throw insertFailure;
                    }
                    storage.giveBack(key, count);
                }));

        assertSame(insertFailure, thrown);
        assertEquals(Arrays.asList("stickx2", "cobblex1"), storage.givenBack);
    }

    @Test
    void aThrowMidLoopGivesBackWhatWasAlreadyDrawnThenRethrows() {
        FakeStorage storage = new FakeStorage();
        storage.stock.put("plank", 10);
        storage.stock.put("stick", 10);
        RuntimeException boom = new IllegalStateException("AE2 blew up");
        storage.failures.put("cobble", boom);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> StationDraw.draw(
                takes("plank", 4, "stick", 2, "cobble", 8), storage::extract, storage::giveBack));

        assertSame(boom, thrown);
        assertEquals(Arrays.asList("plankx4", "stickx2"), storage.givenBack);
    }

    @Test
    void aThrowOnTheFirstEntryHasNothingToGiveBack() {
        FakeStorage storage = new FakeStorage();
        storage.failures.put("plank", new IllegalStateException("AE2 blew up"));

        assertThrows(IllegalStateException.class, () -> StationDraw.draw(
                takes("plank", 4, "stick", 2), storage::extract, storage::giveBack));

        assertEquals(Collections.emptyList(), storage.givenBack);
    }

    @Test
    void aFailingGiveBackDoesNotStopTheRestBeingGivenBackAndIsAttachedAsSuppressed() {
        FakeStorage storage = new FakeStorage();
        storage.stock.put("plank", 10);
        storage.stock.put("stick", 10);
        storage.failures.put("cobble", new IllegalStateException("AE2 blew up"));
        RuntimeException insertFailure = new IllegalStateException("insert failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> StationDraw.draw(
                takes("plank", 4, "stick", 2, "cobble", 8), storage::extract, (key, count) -> {
                    if (key.equals("plank")) {
                        throw insertFailure;
                    }
                    storage.giveBack(key, count);
                }));

        assertEquals("AE2 blew up", thrown.getMessage());
        assertEquals(Arrays.asList("stickx2"), storage.givenBack);
        assertEquals(Arrays.asList(insertFailure), Arrays.asList(thrown.getSuppressed()));
    }
}
