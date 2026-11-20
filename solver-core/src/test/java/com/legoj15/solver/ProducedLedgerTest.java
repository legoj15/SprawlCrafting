package com.legoj15.solver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProducedLedgerTest {

    @Test
    void nothingProducedAdmitsNothing() {
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        assertEquals(0, ledger.outstanding("sword"));
        assertFalse(tally(ledger, "sword", "sword#ench", 2).admits("sword", "sword#ench"));
    }

    @Test
    void admitsUpToTheProducedCountAcrossClaimsAndConsumption() {
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        ledger.produced("sword", 2);
        ledger.produced("sword", 1);
        assertEquals(3, ledger.outstanding("sword"));
        // Three outstanding, three altered copies held: each refused pick claims one, a fourth is over the cap.
        LedgerTally<String, String> craft = tally(ledger, "sword", "sword#ench", 3);
        assertTrue(craft.admits("sword", "sword#ench"));
        craft.picked("sword", "sword#ench", true);
        craft.picked("sword", "sword#ench", true);
        assertTrue(craft.admits("sword", "sword#ench"));
        craft.picked("sword", "sword#ench", true);
        assertFalse(craft.admits("sword", "sword#ench"));
        assertFalse(craft.admits("paxel", "paxel#ench"));

        craft.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));

        // A count already drawn down admits nothing either, and consumption floors at zero.
        ledger.produced("sword", 2);
        ledger.consumed("sword", 2);
        assertEquals(0, ledger.outstanding("sword"));
        assertFalse(tally(ledger, "sword", "sword#ench", 1).admits("sword", "sword#ench"));

        ledger.consumed("sword", 5);
        assertEquals(0, ledger.outstanding("sword"));
    }

    @Test
    void producedCountSaturatesInsteadOfWrapping() {
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        ledger.produced("block", Long.MAX_VALUE);
        ledger.produced("block", 10);
        assertEquals(Long.MAX_VALUE, ledger.outstanding("block"));
    }

    @Test
    void plainPicksLeaveNoCountForThePlayersOwnAlteredCopies() {
        // #13 (a): the player owns 2 enchanted swords before the job starts. The job makes 3 plain
        // swords and a later craft eats all 3. None of the job's swords are left, so no count may
        // remain that would let a later step take the player's enchanted ones.
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", "sword#ench", 2);
        ledger.produced("sword", 3);

        LedgerTally<String, String> craft = tally(ledger, "sword", "sword#ench", 2);
        craft.picked("sword", null, false);
        craft.picked("sword", null, false);
        craft.picked("sword", null, false);
        craft.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));
    }

    @Test
    void onlyAlteredCopiesBeyondThoseHeldAtJobStartAreAdmitted() {
        // The player owns 1 enchanted sword at job start. The job makes 1 sword, which then leaves the
        // pool (the player moves it out): the player's own sword must not be admitted in its place.
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", "sword#ench", 1);
        ledger.produced("sword", 1);
        assertFalse(tally(ledger, "sword", "sword#ench", 1).admits("sword", "sword#ench"));

        // Once a mod enchants the job's copy instead, the pool holds 2 enchanted swords: one is admitted.
        LedgerTally<String, String> craft = tally(ledger, "sword", "sword#ench", 2);
        assertTrue(craft.admits("sword", "sword#ench"));
        craft.picked("sword", "sword#ench", true);
        assertFalse(craft.admits("sword", "sword#ench"));
    }

    @Test
    void aCraftTakingTheAlteredAndThePlainCopyChargesBoth() {
        // #13 (b): the job made 2 swords and a mod enchanted one. One craft takes the enchanted one
        // (admitted by the ledger) and the plain one: both were the job's, nothing is left to describe.
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", "sword#ench", 0);
        ledger.produced("sword", 2);
        LedgerTally<String, String> craft = tally(ledger, "sword", "sword#ench", 1);
        craft.picked("sword", null, false);
        assertTrue(craft.admits("sword", "sword#ench"));
        craft.picked("sword", "sword#ench", true);
        craft.chargeTo(ledger);
        assertEquals(0, ledger.outstanding("sword"));
    }

    @Test
    void aPlainPickKeepsTheCountForTheJobsAlteredCopiesStillHeld() {
        // The player owns 1 enchanted sword and 1 plain one at job start. The job makes 2 swords and a
        // mod enchants both. One craft takes one enchanted sword and the player's plain one: the
        // other enchanted copy of the job's is still held and must stay admissible.
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", "sword#ench", 1);
        ledger.produced("sword", 2);
        LedgerTally<String, String> craft = tally(ledger, "sword", "sword#ench", 3);
        craft.picked("sword", null, false);
        assertTrue(craft.admits("sword", "sword#ench"));
        craft.picked("sword", "sword#ench", true);
        craft.chargeTo(ledger);
        assertEquals(1, ledger.outstanding("sword"));
        assertTrue(tally(ledger, "sword", "sword#ench", 2).admits("sword", "sword#ench"));
    }

    @Test
    void thePlayersDistinctVariantIsNotAdmittedBesideTheJobsAlteredCopy() {
        // The player holds 1 renamed sword at job start; the job produces 1 sword, which a mod
        // enchants. The pool now holds 1 renamed + 1 enchanted copy: only the enchanted one — the
        // job's exact variant — is the job's, whatever order the pool offers them in.
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", "sword#named", 1);
        ledger.produced("sword", 1);
        LedgerTally<String, String> craft = new LedgerTally<>();
        craft.held("sword", "sword#named", 1);
        craft.held("sword", "sword#ench", 1);
        craft.open(ledger);
        assertFalse(craft.admits("sword", "sword#named"));
        assertTrue(craft.admits("sword", "sword#ench"));
    }

    @Test
    void discardingThePlayersOwnVariantMidJobStillAdmitsTheJobs() {
        // Baseline 1 renamed sword; the job produces 1 sword, a mod enchants it, and the player then
        // discards their own renamed copy (drops it, stores it away). The player holds only the job's
        // enchanted variant, which a
        // per-identity baseline (held 1, baseline 1) would refuse and stall the job on.
        ProducedLedger<String, String> ledger = new ProducedLedger<>();
        tally(ledger, "sword", "sword#named", 1);
        ledger.produced("sword", 1);
        assertTrue(tally(ledger, "sword", "sword#ench", 1).admits("sword", "sword#ench"));
    }

    /** A craft's tally over a pool holding {@code held} filter-rejected copies of one {@code variant}, opened against {@code ledger}. */
    private static LedgerTally<String, String> tally(ProducedLedger<String, String> ledger, String key,
                                                     String variant, int held) {
        LedgerTally<String, String> tally = new LedgerTally<>();
        tally.held(key, variant, held);
        tally.open(ledger);
        return tally;
    }
}
