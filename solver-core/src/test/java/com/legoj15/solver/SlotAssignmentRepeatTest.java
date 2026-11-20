package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link SlotAssignment#stableRepeats} protects: the executor's batched craft applies ONE
 * assignment {@code k} times, so if stableRepeats ever over-reports {@code k}, the batch takes items
 * a per-craft run would not — stock would go negative, or a later craft's assignment would differ
 * from the first (a later step of the plan then comes up short). This breaks if someone changes the
 * choice rule or the cap logic without the bound, so it pins the bound itself against the rule.
 */
class SlotAssignmentRepeatTest {

    @Test
    void stableRepeatsNeverOutrunsThePerCraftAssignment() {
        Random random = new Random(0x5EED35L);
        String[] pool = {"a", "b", "c", "d", "e"};
        long biggestR = 0;
        for (int trial = 0; trial < 4000; trial++) {
            // Random slots: 1..9 of them, each accepting 1..3 alternatives of the pool (duplicates
            // across slots allowed, the compressed-block case).
            int slotCount = 1 + random.nextInt(9);
            List<List<String>> slots = new ArrayList<>();
            for (int s = 0; s < slotCount; s++) {
                List<String> alternatives = new ArrayList<>(Arrays.asList(pool));
                Collections.shuffle(alternatives, random);
                slots.add(new ArrayList<>(alternatives.subList(0, 1 + random.nextInt(3))));
            }
            Map<String, Long> stock = new HashMap<>();
            for (String item : pool) {
                stock.put(item, (long) random.nextInt(41));
            }
            // Optionally a made credit: the craft's result is one of the pool items (1..4 of it), so
            // a craft can be credited an item it also consumes.
            Map<String, Long> made = new HashMap<>();
            if (random.nextBoolean()) {
                made.put(pool[random.nextInt(pool.length)], (long) (1 + random.nextInt(4)));
            }

            List<String> first = SlotAssignment.assign(slots, stock);
            if (first.contains(null)) {
                continue; // nothing to repeat: the rule only speaks for assignments that exist
            }
            Map<String, Long> use = new HashMap<>();
            for (String item : first) {
                use.merge(item, 1L, Long::sum);
            }
            long r = SlotAssignment.stableRepeats(slots, item -> stock.getOrDefault(item, 0L), use, made, 200);
            assertTrue(r >= 1, where(trial, slots, stock, made, first, r) + ": r must be at least 1");
            assertTrue(r <= 200, where(trial, slots, stock, made, first, r) + ": r must respect max");
            biggestR = Math.max(biggestR, r);

            // r crafts one by one: each craft's assignment must be exactly the first one, and the
            // stock must never go negative (clamping there would hide an over-reported r).
            for (int craft = 0; craft < r; craft++) {
                assertEquals(first, SlotAssignment.assign(slots, stock),
                        where(trial, slots, stock, made, first, r) + ": craft " + craft + " chose differently");
                for (Map.Entry<String, Long> entry : use.entrySet()) {
                    stock.merge(entry.getKey(), -entry.getValue(), Long::sum);
                }
                for (Map.Entry<String, Long> entry : made.entrySet()) {
                    stock.merge(entry.getKey(), entry.getValue(), Long::sum);
                }
                for (Map.Entry<String, Long> entry : stock.entrySet()) {
                    assertTrue(entry.getValue() >= 0, where(trial, slots, stock, made, first, r)
                            + ": craft " + craft + " drove " + entry.getKey() + " negative");
                }
            }
        }
        // Guard against a generator whose cases all degenerate to r = 1 (the property would be vacuous).
        assertTrue(biggestR > 1, "no trial repeated an assignment; the fuzz never exercised a batch");
    }

    private static String where(int trial, List<List<String>> slots, Map<String, Long> stock,
                                Map<String, Long> made, List<String> first, long r) {
        return "trial " + trial + ": slots " + slots + ", stock " + stock + ", made " + made
                + ", first " + first + ", r " + r;
    }
}
