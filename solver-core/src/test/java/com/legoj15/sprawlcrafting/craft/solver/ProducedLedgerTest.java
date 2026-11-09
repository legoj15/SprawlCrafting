package com.legoj15.sprawlcrafting.craft.solver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProducedLedgerTest {

    @Test
    void nothingProducedAdmitsNothing() {
        ProducedLedger<String> ledger = new ProducedLedger<>();
        assertEquals(0, ledger.outstanding("sword"));
        assertFalse(ledger.canTake("sword", 0));
    }

    @Test
    void admitsUpToTheProducedCountAcrossClaimsAndConsumption() {
        ProducedLedger<String> ledger = new ProducedLedger<>();
        ledger.produced("sword", 2);
        ledger.produced("sword", 1);
        assertEquals(3, ledger.outstanding("sword"));
        assertTrue(ledger.canTake("sword", 2));
        assertFalse(ledger.canTake("sword", 3));
        assertFalse(ledger.canTake("paxel", 0));

        ledger.consumed("sword", 2);
        assertEquals(1, ledger.outstanding("sword"));
        assertTrue(ledger.canTake("sword", 0));
        assertFalse(ledger.canTake("sword", 1));

        ledger.consumed("sword", 5);
        assertEquals(0, ledger.outstanding("sword"));
        assertFalse(ledger.canTake("sword", 0));
    }

    @Test
    void producedCountSaturatesInsteadOfWrapping() {
        ProducedLedger<String> ledger = new ProducedLedger<>();
        ledger.produced("block", Long.MAX_VALUE);
        ledger.produced("block", 10);
        assertEquals(Long.MAX_VALUE, ledger.outstanding("block"));
    }
}
