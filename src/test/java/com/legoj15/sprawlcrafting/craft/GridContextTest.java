package com.legoj15.sprawlcrafting.craft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link GridContext}'s ordinal is a cross-process wire contract: it is sent as an int in
 * {@code DeferredCraftableSyncPayload.grid} (DeferredCraftSync sends {@code grid.ordinal()}, the
 * client reads it back as 0=2x2 / 1=3x3). Reordering the enum constants would silently desync the
 * server's classification from the client's interpretation with no compile error — so pin it.
 *
 * <p>Note: only {@code GridContext.current(ServerPlayer)} touches Minecraft runtime types; the
 * ordinal/dimension contract loads and runs in plain JUnit (the enum's ServerPlayer import is used
 * only inside that method and is not triggered by reading the constants).
 */
class GridContextTest {

    @Test
    void inventoryIsOrdinalZeroAnd2x2() {
        assertEquals(0, GridContext.INVENTORY.ordinal());
        assertEquals(2, GridContext.INVENTORY.width());
        assertEquals(2, GridContext.INVENTORY.height());
    }

    @Test
    void craftingTableIsOrdinalOneAnd3x3() {
        assertEquals(1, GridContext.CRAFTING_TABLE.ordinal());
        assertEquals(3, GridContext.CRAFTING_TABLE.width());
        assertEquals(3, GridContext.CRAFTING_TABLE.height());
    }

    @Test
    void exactlyTwoGridContextsExist_addingOneForcesAWireReview() {
        // A third constant would widen the wire contract; this guard forces a deliberate look.
        assertEquals(2, GridContext.values().length);
    }
}
