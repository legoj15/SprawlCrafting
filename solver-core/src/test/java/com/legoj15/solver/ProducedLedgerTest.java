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
    void plainPicksLeaveNoCountForThePlayersOwnAlteredCopies() {
        // #13 (a): the player owns 2 enchanted swords before the job starts. The job makes 3 plain
        // swords and a later craft eats all 3. None of the job's swords are left, so no count may
        // remain that would let a later step take the player's enchanted ones.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", 2);
        ledger.produced("sword", 3);

        LedgerTally<String> craft = tally(ledger, "sword", 2);
        craft.picked("sword", false);
        craft.picked("sword", false);
        craft.picked("sword", false);
        craft.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));
    }

    @Test
    void onlyAlteredCopiesBeyondThoseHeldAtJobStartAreAdmitted() {
        // The player owns 1 enchanted sword at job start. The job makes 1 sword, which then leaves the
        // pool (the player moves it out): the player's own sword must not be admitted in its place.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", 1);
        ledger.produced("sword", 1);
        assertFalse(tally(ledger, "sword", 1).admissible(ledger).canTake("sword", 0));

        // Once a mod enchants the job's copy instead, the pool holds 2 enchanted swords: one is admitted.
        ProducedLedger<String> view = tally(ledger, "sword", 2).admissible(ledger);
        assertTrue(view.canTake("sword", 0));
        assertFalse(view.canTake("sword", 1));
    }

    @Test
    void aCraftTakingTheAlteredAndThePlainCopyChargesBoth() {
        // #13 (b): the job made 2 swords and a mod enchanted one. One craft takes the enchanted one
        // (admitted by the ledger) and the plain one: both were the job's, nothing is left to describe.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", 0);
        ledger.produced("sword", 2);
        LedgerTally<String> craft = tally(ledger, "sword", 1);
        craft.picked("sword", false);
        assertTrue(craft.admissible(ledger).canTake("sword", craft.refusedTakes().getOrDefault("sword", 0)));
        craft.picked("sword", true);
        craft.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));
    }

    @Test
    void aPlainPickKeepsTheCountForTheJobsAlteredCopiesStillHeld() {
        // The player owns 1 enchanted sword and 1 plain one at job start. The job makes 2 swords and a
        // mod enchants both. One craft takes one enchanted sword and the player's plain one: the
        // other enchanted copy of the job's is still held and must stay admissible.
        ProducedLedger<String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", 1);
        ledger.produced("sword", 2);
        LedgerTally<String> craft = tally(ledger, "sword", 3);
        craft.picked("sword", false);
        craft.picked("sword", true);
        craft.chargeTo(ledger);
        assertEquals(1, ledger.outstanding("sword"));
        assertTrue(tally(ledger, "sword", 2).admissible(ledger).canTake("sword", 0));
    }

    /** A craft's tally over a pool holding {@code held} filter-rejected copies of {@code key}, opened against {@code ledger}. */
    private static LedgerTally<String> tally(ProducedLedger<String> ledger, String key, int held) {
        LedgerTally<String> tally = new LedgerTally<>();
        tally.held(key, held);
        tally.admissible(ledger);
        return tally;
    }
}
