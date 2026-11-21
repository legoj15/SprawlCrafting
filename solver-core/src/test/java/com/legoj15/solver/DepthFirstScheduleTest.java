package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The depth-first walk of a long plan, driven by a simulated executor: raw items are unbounded,
 * intermediates are tracked with a capacity on their SUM, and a batch's output is clamped to the
 * room the storage offers the batch as a whole — net of what the batch itself consumes, the room a
 * single craft always finds in the game because the craft consumes before it inserts. A batch that
 * fits no room even net ({@code n = 0}) reports {@code noRoom()}, which must be able to shrink the
 * batch: a schedule that reports the storage full while its own consuming steps could still drain
 * it would cancel jobs that are makeable. The class under test is Minecraft-free, so these run
 * without a game.
 */
class DepthFirstScheduleTest {

    private static final int CHAIN_LEVELS = 6;
    private static final long CHAIN_TOP = 17_000_000_000L; // leaf step: 1.003833e15 crafts
    private static final long INT_BATCH = 1L << 31;

    /** A plan's tables, as {@link DepthFirstSchedule}'s constructor takes them. */
    private static final class Plan {
        final long[] crafts;
        final List<List<SlotLayout<String>>> layouts;
        final List<String> results;
        final int[] resultCounts;
        final Map<String, Long> held = new HashMap<>();

        Plan(long[] crafts, List<List<SlotLayout<String>>> layouts, List<String> results,
             int[] resultCounts) {
            this.crafts = crafts;
            this.layouts = layouts;
            this.results = results;
            this.resultCounts = resultCounts;
        }
    }

    /** A fan-in-9 compression chain: 9 raws -> i1, 9 i_k -> i(k+1), ..., 9 i5 -> target. */
    private static Plan chain(long topTotal) {
        long[] crafts = new long[CHAIN_LEVELS];
        int[] resultCounts = new int[CHAIN_LEVELS];
        List<List<SlotLayout<String>>> layouts = new ArrayList<>();
        List<String> results = new ArrayList<>();
        crafts[CHAIN_LEVELS - 1] = topTotal;
        for (int k = CHAIN_LEVELS - 2; k >= 0; k--) {
            crafts[k] = crafts[k + 1] * 9;
        }
        for (int k = 0; k < CHAIN_LEVELS; k++) {
            String input = k == 0 ? "nugget" : "i" + k;
            results.add(k == CHAIN_LEVELS - 1 ? "target" : "i" + (k + 1));
            resultCounts[k] = 1;
            List<String> slots = new ArrayList<>();
            for (int s = 0; s < 9; s++) {
                slots.add(input);
            }
            List<SlotLayout<String>> runs = new ArrayList<>();
            runs.add(new SlotLayout<>(crafts[k], slots));
            layouts.add(runs);
        }
        return new Plan(crafts, layouts, results, resultCounts);
    }

    /**
     * The simulated executor: counts of the plan's intermediates (everything but the target, which
     * the player asked for and the final craft hands to the grid) with a capacity on their sum.
     */
    private static final class FakeStorage {
        private final Plan plan;
        private final long capacity;
        private final int top;
        private final Map<String, Long> stock = new HashMap<>();
        private final long[] done;
        /** Every step's done counts at the moment that step first ran (null steps never ran). */
        private final long[][] doneAtFirstRun;

        private long held;
        private long maxHeld;
        private long batches;
        private long noRoomCalls;
        private int finalBatches;

        private FakeStorage(Plan plan, long capacity) {
            this.plan = plan;
            this.capacity = capacity;
            this.top = plan.crafts.length - 1;
            this.done = new long[plan.crafts.length];
            this.doneAtFirstRun = new long[plan.crafts.length][];
            for (int k = 0; k < top; k++) { // the target is not an intermediate: not counted
                Long start = plan.held.get(plan.results.get(k));
                long count = start == null ? 0 : start;
                stock.put(plan.results.get(k), count);
                held += count;
            }
            maxHeld = held;
        }

        /** How many crafts of step {@code step}'s current batch the storage affords right now. */
        private long afford(int step, long batch) {
            if (step == top) {
                return batch;
            }
            long countedUse = 0;
            for (long per : uses(step).values()) {
                countedUse += per;
            }
            long net = plan.resultCounts[step] - countedUse;
            return net <= 0 ? batch : Math.min(batch, Math.max(0, (capacity - held) / net));
        }

        /** Applies {@code n} crafts of step {@code step}, refusing to go below zero stock. */
        private void apply(int step, long n) {
            for (Map.Entry<String, Long> use : uses(step).entrySet()) {
                long have = stock.get(use.getKey());
                assertTrue(have >= n * use.getValue(),
                        "storage would go negative for " + use.getKey());
                stock.put(use.getKey(), have - n * use.getValue());
            }
            if (step != top) {
                stock.merge(plan.results.get(step), n * plan.resultCounts[step], Long::sum);
            }
            if (doneAtFirstRun[step] == null) {
                doneAtFirstRun[step] = done.clone();
            }
            done[step] += n;
            batches++;
            held = 0;
            for (long count : stock.values()) {
                held += count;
            }
            maxHeld = Math.max(maxHeld, held);
            assertTrue(held <= capacity, "held sum " + held + " over capacity " + capacity);
        }

        /** The tracked per-item uses of the layout run covering the step's done count. */
        private Map<String, Long> uses(int step) {
            List<SlotLayout<String>> runs = plan.layouts.get(step);
            long at = 0;
            List<String> items = runs.get(runs.size() - 1).items();
            for (SlotLayout<String> run : runs) {
                if (done[step] < at + run.crafts()) {
                    items = run.items();
                    break;
                }
                at += run.crafts();
            }
            Map<String, Long> use = new LinkedHashMap<>();
            for (String item : items) {
                if (stock.containsKey(item)) { // raw items are not tracked
                    use.merge(item, 1L, Long::sum);
                }
            }
            return use;
        }

        /** Drives the schedule to completion; the guard catches a walk that stops terminating. */
        private void drive(DepthFirstSchedule<String> schedule, long iterationCap) {
            long iterations = 0;
            while (!schedule.finished()) {
                int step = schedule.current();
                long batch = schedule.batch();
                assertTrue(batch >= 1, "a running batch holds at least one craft");
                if (schedule.isFinalBatch()) {
                    finalBatches++;
                    for (int i = 0; i < plan.crafts.length - 1; i++) {
                        assertEquals(plan.crafts[i], schedule.done(i),
                                "every other step finished on the final batch");
                    }
                }
                long n = afford(step, batch);
                if (n == 0) {
                    noRoomCalls++;
                    assertTrue(schedule.noRoom(), "noRoom refused at step " + step
                            + " (cap " + schedule.cap() + ", batch " + batch + ")");
                    continue;
                }
                apply(step, n);
                schedule.performed(n);
                assertTrue(++iterations <= iterationCap, "the plan did not terminate");
            }
        }
    }

    /**
     * A 6-level compression chain with ~1e15 leaf crafts on a 2.3e9-capacity storage (a large AE2
     * network): every step must reach its exact total, nothing may go negative, the held sum never
     * exceeds the capacity, and the batch count stays within a small factor of the int-sized ideal —
     * one batch per {@code 2^31} items of volume per step.
     */
    @Test
    void aCompressionChainOnARoomyStorageCompletesEveryTotalInIntSizedBatches() {
        Plan chain = chain(CHAIN_TOP);
        FakeStorage storage = new FakeStorage(chain, 2_300_000_000L);
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                chain.crafts, chain.layouts, chain.results, chain.resultCounts, chain.held);
        storage.drive(schedule, 200_000_000L);
        for (int i = 0; i < chain.crafts.length; i++) {
            assertEquals(chain.crafts[i], schedule.done(i), "step " + i + " reached its exact total");
        }
        long ideal = 0;
        for (long crafts : chain.crafts) {
            ideal += (crafts * 9 + INT_BATCH - 1) / INT_BATCH; // max(resultCount, slots) = 9
        }
        System.out.println("DepthFirstScheduleTest chain R=2.3e9: " + storage.batches + " batches"
                + " (ideal " + ideal + ", steps " + chain.crafts.length + "), max held "
                + storage.maxHeld + ", noRoom " + storage.noRoomCalls + "x");
        assertTrue(storage.batches <= 4 * (ideal + chain.crafts.length),
                "batches " + storage.batches + " far over 4x the int-sized ideal " + ideal);
    }

    /**
     * The same chain shape on a 1e8 storage — smaller than one int-sized batch's output — run at a
     * hundredth of the totals (the R-limited cycle count grows with the total volume per unit of
     * room, and the full-size walk is a minute of pure arithmetic for no extra coverage): the job
     * still completes exactly, the held sum respects the capacity, and a full storage never reads as
     * a dead end — the shrinking cap always finds a batch that fits or a step that drains.
     */
    @Test
    void aChainOnATightStorageCompletesWithoutFalseFull() {
        Plan chain = chain(CHAIN_TOP / 100);
        FakeStorage storage = new FakeStorage(chain, 100_000_000L);
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                chain.crafts, chain.layouts, chain.results, chain.resultCounts, chain.held);
        storage.drive(schedule, 200_000_000L);
        for (int i = 0; i < chain.crafts.length; i++) {
            assertEquals(chain.crafts[i], schedule.done(i), "step " + i + " reached its exact total");
        }
        System.out.println("DepthFirstScheduleTest chain R=1e8: " + storage.batches + " batches, noRoom "
                + storage.noRoomCalls + "x");
        assertTrue(storage.maxHeld <= 100_000_000L, "held sum " + storage.maxHeld + " over capacity");
    }

    /**
     * A held stock above an int (5e9 of the level-1 intermediate, the leaf's total reduced to
     * match): the held count is used exactly — the leaf step first runs only once what is left of
     * it cannot supply the next batch any more (one int-sized batch's input need at most) — and the
     * plan still completes to the exact totals.
     */
    @Test
    void aHeldStockAboveAnIntIsUsedBeforeTheProducerRuns() {
        Plan chain = chain(2_000_000L);
        long heldIntermediate = 5_000_000_000L;
        chain.held.put("i1", heldIntermediate);
        chain.crafts[0] -= heldIntermediate; // the leaf makes exactly the shortfall
        FakeStorage storage = new FakeStorage(chain, Long.MAX_VALUE / 4);
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                chain.crafts, chain.layouts, chain.results, chain.resultCounts, chain.held);
        storage.drive(schedule, 2_000_000L);
        for (int i = 0; i < chain.crafts.length; i++) {
            assertEquals(chain.crafts[i], schedule.done(i), "step " + i + " reached its exact total");
        }
        assertTrue(storage.doneAtFirstRun[0] != null, "the leaf step ran at least once");
        long consumedWhenLeafFirstRan = 9 * storage.doneAtFirstRun[0][1];
        assertTrue(heldIntermediate - consumedWhenLeafFirstRan < INT_BATCH,
                "leaf ran while more than one int-sized batch's need ("
                        + (heldIntermediate - consumedWhenLeafFirstRan) + ") of the held stock remained");
    }

    /**
     * A shared intermediate consumed by two different steps, one of which carries two layout runs
     * over different items (x+y, then x plus an uncrafted raw): the walk must feed both consumers
     * from the one producer, follow the run boundary when it re-reads the step's inputs, and still
     * land every exact total with no negative stock. The plan over-makes m by 20 (10 crafts of
     * step 2); nothing asks for them, so those crafts are skipped, never run.
     */
    @Test
    void aSharedIntermediateAndTwoLayoutRunsCompleteExactly() {
        // step0: 9 raws -> x; step1: reed -> y; step2: (x+y) then (x+w) -> 2m; step3: x+m -> n;
        // step4: m+n -> target. x: 50 + 40 = 90 planned; y: 20 used; m: 100 planned, 80 used.
        Plan plan = new Plan(
                new long[] {90, 20, 50, 40, 40},
                Arrays.asList(
                        runs(new SlotLayout<>(90, items("raw", "raw", "raw", "raw", "raw", "raw", "raw", "raw", "raw"))),
                        runs(new SlotLayout<>(20, items("reed"))),
                        runs(new SlotLayout<>(20, items("x", "y")), new SlotLayout<>(30, items("x", "w"))),
                        runs(new SlotLayout<>(40, items("x", "m"))),
                        runs(new SlotLayout<>(40, items("m", "n")))),
                Arrays.asList("x", "y", "m", "n", "target"),
                new int[] {1, 1, 2, 1, 1});
        FakeStorage storage = new FakeStorage(plan, Long.MAX_VALUE / 4);
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                plan.crafts, plan.layouts, plan.results, plan.resultCounts, plan.held);
        storage.drive(schedule, 1_000L);
        for (int i = 0; i < plan.crafts.length; i++) {
            assertEquals(plan.crafts[i], schedule.done(i), "step " + i + " reached its exact total");
        }
        assertEquals(40L, storage.done[2], "step 2 runs only the 40 crafts the consumers need");
        assertEquals(0L, storage.stock.get("m").longValue(), "no surplus m is made");
    }

    /**
     * {@code isFinalBatch()} is true on the job's very last batch alone, and on that batch every
     * other step is already done.
     */
    @Test
    void theFinalBatchFlagIsTrueOnlyOnTheVeryLastBatch() {
        Plan chain = chain(5L); // 405 / 45 / 5 crafts: a few batches per step
        FakeStorage storage = new FakeStorage(chain, Long.MAX_VALUE / 4);
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                chain.crafts, chain.layouts, chain.results, chain.resultCounts, chain.held);
        storage.drive(schedule, 10_000L);
        assertEquals(1, storage.finalBatches, "exactly one final batch");
        for (int i = 0; i < chain.crafts.length; i++) {
            assertEquals(chain.crafts[i], schedule.done(i), "step " + i + " reached its exact total");
        }
    }

    /**
     * A single target craft whose demand the held stock already covers partway down (the playtest's
     * Evolved Divine Crystal: one craft, billions of gold ingots held): the steps below the held item are
     * surplus to the model and must never run — making them first would fill the storage with an
     * intermediate nothing asked for — and the job still finishes, the target last.
     */
    @Test
    void aOneCraftTargetNeverRunsStepsItsHeldStockMakesSurplus() {
        Plan chain = chain(1L); // 59049 / 6561 / 729 / 81 / 9 / 1 crafts
        chain.held.put("i3", 1_000_000_000_000L); // covers step 3's 729 inputs many times over
        FakeStorage storage = new FakeStorage(chain, Long.MAX_VALUE / 4);
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                chain.crafts, chain.layouts, chain.results, chain.resultCounts, chain.held);
        storage.drive(schedule, 10_000L);
        for (int k = 0; k < 3; k++) {
            assertNull(storage.doneAtFirstRun[k], "surplus step " + k + " ran");
        }
        for (int k = 3; k < chain.crafts.length; k++) {
            assertEquals(chain.crafts[k], storage.done[k], "step " + k + " reached its exact total");
        }
        assertTrue(schedule.finished());
        assertEquals(1, storage.finalBatches, "exactly one final batch");
    }

    /**
     * A full storage where the first branch the target demands cannot place even one craft (A, made
     * from raws), while another branch consumes stock already held (9 b -> c frees room): the
     * refusal must send the walk to the other branch, whose crafts make the room A needs, rather
     * than report the storage full and cancel.
     */
    @Test
    void aRefusedBranchFallsBackToABranchThatDrainsTheStorage() {
        // step0: raw -> b (1 craft); step1: raw -> a; step2: 9 b -> c; step3: a + c -> target.
        Plan plan = new Plan(
                new long[] {1, 10, 10, 10},
                Arrays.asList(
                        runs(new SlotLayout<>(1, items("raw"))),
                        runs(new SlotLayout<>(10, items("raw"))),
                        runs(new SlotLayout<>(10, items("b", "b", "b", "b", "b", "b", "b", "b", "b"))),
                        runs(new SlotLayout<>(10, items("a", "c")))),
                Arrays.asList("b", "a", "c", "target"),
                new int[] {1, 1, 1, 1});
        plan.held.put("b", 89L);
        FakeStorage storage = new FakeStorage(plan, 89L); // exactly full
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                plan.crafts, plan.layouts, plan.results, plan.resultCounts, plan.held);
        storage.drive(schedule, 10_000L);
        for (int i = 0; i < plan.crafts.length; i++) {
            assertEquals(plan.crafts[i], storage.done[i], "step " + i + " reached its exact total");
        }
    }

    /**
     * The target's own craft refused room with every input held: no branch can free space for it, so
     * {@code noRoom()} must report full within the halving bound (not loop on the same refused craft),
     * and the schedule must still name the refused step for the caller's log line.
     */
    @Test
    void aRefusedTargetCraftReportsFullAndKeepsItsStep() {
        Plan chain = chain(1L);
        chain.held.put("i5", 9L); // the target's nine inputs are held
        DepthFirstSchedule<String> schedule = new DepthFirstSchedule<>(
                chain.crafts, chain.layouts, chain.results, chain.resultCounts, chain.held);
        assertEquals(CHAIN_LEVELS - 1, schedule.current());
        int calls = 0;
        while (schedule.noRoom()) {
            assertTrue(++calls < 64, "noRoom kept reporting room for a craft nothing can place");
        }
        assertEquals(CHAIN_LEVELS - 1, schedule.current(), "the refused step stays current");
    }

    private static List<SlotLayout<String>> runs(SlotLayout<String>... runList) {
        return new ArrayList<>(Arrays.asList(runList));
    }

    private static List<String> items(String... itemArray) {
        return Arrays.asList(itemArray);
    }
}
