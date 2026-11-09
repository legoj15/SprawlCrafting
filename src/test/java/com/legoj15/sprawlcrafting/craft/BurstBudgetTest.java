package com.legoj15.sprawlcrafting.craft;

import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The arithmetic behind the shared per-server-tick burst budget (issue #17): what each burst may spend,
 * that the total stays bounded however many players burst, that a burst's overshoot never shrinks the
 * players after it in the loop, and that the window follows the server tick.
 *
 * <p>The "every job still crafts at least once" guarantee is not testable here: it is structural, the
 * burst loop in {@code CraftQueueManager} checks the clock after a craft, never before, so a grant of 0
 * still lets one craft through. That loop needs a live ServerPlayer; what this pins is that a spent
 * budget grants exactly 0 (never negative, never an exception) and that the next tick refills.
 */
class BurstBudgetTest {

    private static final long TICK = CraftJob.SERVER_TICK_BURST_BUDGET_NANOS;
    private static final long CAP = CraftJob.BURST_BUDGET_NANOS;

    private static IntSupplier jobs(int n) {
        return () -> n;
    }

    @Test
    void aFewBulkJobsEachKeepTheFullPerJobCap() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        long total = 0;
        for (int i = 0; i < 3; i++) {
            long grant = budget.grant(1, jobs(3));
            assertEquals(CAP, grant);
            budget.charge(grant);
            total += grant;
        }
        assertEquals(3 * CAP, total);
    }

    @Test
    void manyBulkJobsSplitTheTickEvenly() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        long total = 0;
        for (int i = 0; i < 20; i++) {
            long grant = budget.grant(7, jobs(20));
            assertEquals(TICK / 20, grant);
            total += grant;
            budget.charge(grant);
        }
        assertEquals(TICK, total); // 20 players cost the tick budget, not 20 x 2 ms
    }

    @Test
    void aBurstsOvershootDoesNotShrinkThePlayersAfterIt() {
        // The clock is checked after a craft, so every burst runs up to one craft past its grant. Were that
        // overshoot taken from the shared window, each later player in the (fixed) loop order would get
        // less, and the last would sit at the one-craft floor every tick.
        BurstBudget budget = new BurstBudget(TICK, CAP);
        long oneCraft = 60_000L; // inside the measured 40-95 us per craft
        long first = budget.grant(7, jobs(20));
        budget.charge(first + oneCraft);
        for (int i = 1; i < 20; i++) {
            long grant = budget.grant(7, jobs(20));
            assertEquals(first, grant, "player " + i + " in the loop");
            budget.charge(grant + oneCraft);
        }
    }

    @Test
    void moreBurstsThanCountedShareWhatIsLeftAndThenGetZeroUntilTheNextTick() {
        // The job count is taken once, when the tick's first burst opens the window; a job that starts
        // bursting later in the same tick is not in it, and must not push the tick past its budget.
        BurstBudget budget = new BurstBudget(TICK, CAP);
        for (int i = 0; i < 5; i++) {
            assertEquals(CAP, budget.grant(1, jobs(1)));
            budget.charge(CAP);
        }
        assertEquals(0L, budget.grant(1, jobs(1)));
        budget.charge(5 * CAP); // a zero grant charges nothing, and remaining never goes negative
        assertEquals(0L, budget.grant(1, jobs(1)));
        assertEquals(CAP, budget.grant(2, jobs(1))); // the next server tick starts fresh
    }

    @Test
    void aBurstThatEndsEarlyLeavesItsUnusedTimeForTheRest() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        budget.grant(1, jobs(1));
        budget.charge(CAP / 4); // hit the 64-craft cap or a step boundary well inside its grant
        for (int i = 0; i < 4; i++) {
            assertEquals(CAP, budget.grant(1, jobs(1)));
            budget.charge(CAP);
        }
        assertEquals(CAP - CAP / 4, budget.grant(1, jobs(1)));
    }

    @Test
    void anyChangeOfServerTickOpensAFreshWindowSoABackwardsClockCannotWedgeIt() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        spend(budget, 1000);
        assertEquals(0L, budget.grant(1000, jobs(1)));
        assertEquals(CAP, budget.grant(3, jobs(1))); // tick went backwards (new server / wrapped counter)
        spend(budget, 3);
        budget.reset(); // server shutdown: even the same tick number must not inherit a spent window
        assertEquals(CAP, budget.grant(3, jobs(1)));
    }

    /** Spends the whole window of {@code tick} in full per-job bursts. */
    private static void spend(BurstBudget budget, long tick) {
        long grant;
        while ((grant = budget.grant(tick, jobs(1))) > 0) {
            budget.charge(grant);
        }
    }
}
