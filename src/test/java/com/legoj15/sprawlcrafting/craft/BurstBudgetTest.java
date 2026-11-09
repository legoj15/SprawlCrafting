package com.legoj15.sprawlcrafting.craft;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The arithmetic behind the shared per-server-tick burst budget (issue #17): what each burst may spend,
 * that the total stays bounded however many players burst, and that the window follows the server tick.
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
    void manyBulkJobsSplitTheTickEvenlyWhateverTheirPositionAndTheCountIsTakenOnce() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        AtomicInteger asked = new AtomicInteger();
        IntSupplier twenty = () -> {
            asked.incrementAndGet();
            return 20;
        };
        long first = -1;
        long last = -1;
        long total = 0;
        for (int i = 0; i < 20; i++) {
            long grant = budget.grant(7, twenty);
            if (i == 0) {
                first = grant;
            }
            last = grant;
            total += grant;
            budget.charge(grant); // every burst uses exactly what it was granted
        }
        assertEquals(TICK / 20, first);
        assertEquals(first, last); // the last player in the loop is not worse off than the first
        assertEquals(TICK, total); // 20 players cost the tick budget, not 20 x 2 ms
        assertEquals(1, asked.get());
    }

    @Test
    void anOvershootingBurstShrinksLaterOnesAndASpentBudgetGrantsZeroUntilTheNextTick() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        assertEquals(CAP, budget.grant(1, jobs(1)));
        budget.charge(TICK + CAP); // the burst blew far past the whole tick budget
        assertEquals(0L, budget.grant(1, jobs(1)));
        assertEquals(0L, budget.grant(1, jobs(1))); // stays at 0, not negative, for everyone after it
        assertEquals(CAP, budget.grant(2, jobs(1))); // the next server tick starts fresh
    }

    @Test
    void anyChangeOfServerTickOpensAFreshWindowSoABackwardsClockCannotWedgeIt() {
        BurstBudget budget = new BurstBudget(TICK, CAP);
        budget.grant(1000, jobs(1));
        budget.charge(TICK);
        assertEquals(0L, budget.grant(1000, jobs(1)));
        assertEquals(CAP, budget.grant(3, jobs(1))); // tick went backwards (new server / wrapped counter)
        budget.charge(TICK);
        budget.reset(); // server shutdown: even the same tick number must not inherit a spent window
        assertEquals(CAP, budget.grant(3, jobs(1)));
    }
}
