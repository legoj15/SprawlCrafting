package com.legoj15.sprawlcrafting.craft;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The round walk of an interleaved job: every step must reach its exact total, no intermediate may
 * build up beyond about one round's output, and the cumulative-target math must stay exact where
 * {@code crafts * r} would overflow a long. The class under test is Minecraft-free, so these run
 * without a game.
 */
class RoundScheduleTest {

    /**
     * A 3-step compression chain (9 nuggets to an ingot, 9 ingots to a block, 9 blocks to the target),
     * totals 81·T / 9·T / T, driven by a fake stock map. Executing the steps one after another would
     * hold 81T ingots at once; interleaved rounds must complete every total while never holding much
     * beyond one round's worth of either intermediate.
     */
    @Test
    void anInterleavedCompressionChainCompletesEveryTotalWithoutStockpiling() {
        long targets = 1_000_000_000_000L;
        long roundSize = 1_000_000_000L;
        long[] crafts = {81L * targets, 9L * targets, targets};
        Map<String, Long> stock = new HashMap<>();
        stock.put("nugget", 9L * 81L * targets);
        stock.put("ingot", 0L);
        stock.put("block", 0L);
        stock.put("target", 0L);
        String[] ingredient = {"nugget", "ingot", "block"};
        String[] output = {"ingot", "block", "target"};
        long[] perCraft = {9, 9, 9};

        RoundSchedule schedule = new RoundSchedule(crafts, roundSize);
        assertTrue(schedule.interleaved());
        long maxIngots = 0;
        long maxBlocks = 0;
        long iterations = 0;
        while (!schedule.finished()) {
            int step = schedule.current();
            long n = Math.min(schedule.leftInRound(), stock.get(ingredient[step]) / perCraft[step]);
            if (n == 0) {
                assertTrue(schedule.defer(), "a deferred step must not stick the chain");
                continue;
            }
            stock.put(ingredient[step], stock.get(ingredient[step]) - n * perCraft[step]);
            stock.put(output[step], stock.get(output[step]) + n);
            maxIngots = Math.max(maxIngots, stock.get("ingot"));
            maxBlocks = Math.max(maxBlocks, stock.get("block"));
            schedule.performed(n);
            if (++iterations > 10_000_000) {
                throw new AssertionError("the chain did not terminate: " + iterations + " iterations");
            }
        }
        for (int i = 0; i < crafts.length; i++) {
            assertEquals(crafts[i], schedule.done(i), "step " + i + " reached its exact total");
        }
        assertEquals(targets, stock.get("target").longValue());
        assertEquals(0L, stock.get("nugget").longValue());
        assertEquals(91L * targets, schedule.totalDone());
        assertTrue(maxIngots <= 2 * 9 * roundSize, "ingots held: " + maxIngots);
        assertTrue(maxBlocks <= 2 * 9 * roundSize, "blocks held: " + maxBlocks);
        assertEquals((81L * targets + roundSize - 1) / roundSize, schedule.round(),
                "rounds == ceil(81T / roundSize)");
    }

    /**
     * The cumulative target {@code floor(crafts * r / N)} for a total near {@code Long.MAX_VALUE / 2}
     * and enough rounds that {@code rem * r} overflows a long: every probed round matches the exact
     * BigInteger quotient, and the final round's target is the total itself, exactly.
     */
    @Test
    void cumulativeTargetsAreExactNearHalfOfLongMax() {
        long rounds = 3_200_000_000L;
        long crafts = 1_441_151_880L * rounds + rounds - 1; // 4611686019199999999 > Long.MAX_VALUE / 2
        assertTrue(crafts > Long.MAX_VALUE / 2);
        long rem = crafts % rounds;
        assertTrue(BigInteger.valueOf(rem).multiply(BigInteger.valueOf(rounds))
                .compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0, "rem * rounds must overflow a long");

        for (long r : new long[] {1, 2, rounds / 2, rounds - 1}) {
            long expected = BigInteger.valueOf(crafts).multiply(BigInteger.valueOf(r))
                    .divide(BigInteger.valueOf(rounds)).longValueExact();
            assertEquals(expected, RoundSchedule.cumulativeTarget(crafts, r, rounds), "r = " + r);
        }
        assertEquals(crafts, RoundSchedule.cumulativeTarget(crafts, rounds, rounds), "final target exact");
    }

    /**
     * A step that can never run (its ingredient never arrives) must not loop the schedule forever:
     * once a full-target pass makes no progress, {@code defer()} reports the job stuck, and it stays
     * stuck. A stalled ordinary round is not stuck — later rounds still retry the step.
     */
    @Test
    void aStarvedDrainPassSticksTheSchedule() {
        RoundSchedule schedule = new RoundSchedule(new long[] {4, 4}, 2);
        assertTrue(schedule.interleaved());
        boolean stuck = false;
        for (int i = 0; i < 100 && !schedule.finished(); i++) {
            if (schedule.current() == 0) {
                schedule.performed(schedule.leftInRound()); // step 0 always runs
            } else if (!schedule.defer()) { // step 1 never gets its ingredient
                stuck = true;
                break;
            }
        }
        assertTrue(stuck, "a drain pass with no progress reports the job stuck");
        assertFalse(schedule.finished());
        assertFalse(schedule.defer(), "a stuck schedule stays stuck");
        assertEquals(4L, schedule.done(0));
    }
}
