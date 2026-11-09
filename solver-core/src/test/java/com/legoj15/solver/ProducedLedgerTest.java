package com.legoj15.solver;

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

    @Test
    void plainPicksBeyondTheHeldAlteredCopiesDrainTheCount() {
        // The job made 3 plain swords; the player's own enchanted sword sits in the pool. A craft
        // eats the 3 plain ones. Only one count may be kept back (for the one altered copy held),
        // so a later step can admit at most one altered sword, not three.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        ledger.produced("sword", 3);
        LedgerTally<String> tally = new LedgerTally<>();
        tally.held("sword", 1);
        tally.picked("sword", false);
        tally.picked("sword", false);
        tally.picked("sword", false);
        tally.chargeTo(ledger);
        assertEquals(1, ledger.outstanding("sword"));
        assertFalse(ledger.canTake("sword", 1));
    }

    @Test
    void aCraftTakingTheAlteredAndThePlainCopyChargesBoth() {
        // The job made 2 swords and a mod enchanted one. One craft takes the enchanted one (admitted
        // by the ledger) and the plain one: both were the job's, nothing is left to describe.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        ledger.produced("sword", 2);
        LedgerTally<String> tally = new LedgerTally<>();
        tally.held("sword", 1);
        tally.picked("sword", false);
        assertTrue(ledger.canTake("sword", tally.refusedTakes().getOrDefault("sword", 0)));
        tally.picked("sword", true);
        tally.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));
    }

    @Test
    void aPlainPickDoesNotSpendHeadroomAnAlteredCopyStillHeldNeeds() {
        // #12: the job's one sword was enchanted, the player owns a plain one, the recipe needs both.
        // The plain pick must not use up the headroom the enchanted pick needs.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        ledger.produced("sword", 1);
        LedgerTally<String> tally = new LedgerTally<>();
        tally.held("sword", 1);
        tally.picked("sword", false);
        assertTrue(ledger.canTake("sword", tally.refusedTakes().getOrDefault("sword", 0)));
        tally.picked("sword", true);
        tally.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));

        // Both of the job's 2 swords were enchanted; one craft takes one of them plus the player's
        // plain sword. The other enchanted copy is still the job's and must stay admissible.
        ledger.produced("sword", 2);
        tally = new LedgerTally<>();
        tally.held("sword", 2);
        tally.picked("sword", false);
        tally.picked("sword", true);
        tally.chargeTo(ledger);
        assertEquals(1, ledger.outstanding("sword"));
        assertTrue(ledger.canTake("sword", 0));
    }
}
