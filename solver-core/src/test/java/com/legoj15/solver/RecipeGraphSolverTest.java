package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.legoj15.solver.RecipeGraphSolver.RecipeInfo;
import com.legoj15.solver.RecipeGraphSolver.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGraphSolverTest {

    /** String-typed recipe for tests: id, slots (each "a|b" = alternatives), result, count. */
    private static final class TestRecipe implements RecipeInfo<String, String> {
        private final String id;
        private final List<List<String>> ingredientSlots;
        private final List<List<String>> craftableIngredientSlots;
        private final String result;
        private final int resultCount;

        TestRecipe(String id, List<List<String>> ingredientSlots,
                   List<List<String>> craftableIngredientSlots, String result, int resultCount) {
            this.id = id;
            this.ingredientSlots = ingredientSlots;
            this.craftableIngredientSlots = craftableIngredientSlots;
            this.result = result;
            this.resultCount = resultCount;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<List<String>> ingredientSlots() {
            return ingredientSlots;
        }

        @Override
        public List<List<String>> craftableIngredientSlots() {
            return craftableIngredientSlots;
        }

        @Override
        public String result() {
            return result;
        }

        @Override
        public int resultCount() {
            return resultCount;
        }

        static TestRecipe of(String id, String result, int resultCount, String... slots) {
            List<List<String>> parsed = parse(slots);
            return new TestRecipe(id, parsed, parsed, result, resultCount);
        }

        /**
         * Like {@link #of} but marks the given item identities as INVENTORY-ONLY: matchable from
         * stock but never craftable (a filled bucket, whose crafted form would come out empty). The
         * craftable subset excludes them, mirroring the 1.12.2 adapter's {@code ingredient.apply}
         * filter, while the full alternative set (used for inventory matching) still includes them.
         */
        static TestRecipe ofInventoryOnly(String id, String result, int resultCount,
                                          List<String> inventoryOnly, String... slots) {
            List<List<String>> parsed = parse(slots);
            List<List<String>> craftable = new ArrayList<>();
            for (List<String> slot : parsed) {
                List<String> filtered = new ArrayList<>(slot);
                filtered.removeAll(inventoryOnly);
                craftable.add(filtered);
            }
            return new TestRecipe(id, parsed, craftable, result, resultCount);
        }

        private static List<List<String>> parse(String... slots) {
            List<List<String>> parsed = new ArrayList<>();
            for (String slot : slots) {
                parsed.add(Arrays.asList(slot.split("\\|")));
            }
            return parsed;
        }
    }

    private final List<TestRecipe> recipes = new ArrayList<>();
    private final Map<String, String> remainders = new HashMap<>();

    private RecipeGraphSolver<String, String> solver() {
        return solver(32, 4096);
    }

    private RecipeGraphSolver<String, String> solver(int maxDepth, int maxAttempts) {
        return new RecipeGraphSolver<>(
                item -> recipes.stream().filter(r -> r.result().equals(item)).collect(Collectors.toList()),
                remainders::get,
                maxDepth, maxAttempts);
    }

    private static Map<String, Integer> inv(Object... pairs) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    private List<PlannedStep<String>> solveOk(TestRecipe target, Map<String, Integer> inventory) {
        Result<String, String> result = solver().solve(target, inventory);
        assertInstanceOf(Result.Success.class, result, "expected solvable, got " + result);
        return ((Result.Success<String, String>) result).steps();
    }

    @Test
    void allIngredientsInStockYieldsSingleStep() {
        TestRecipe stick = TestRecipe.of("stick", "stick", 4, "planks", "planks");
        assertEquals(Arrays.asList(new PlannedStep<>("stick", 1)),
                solveOk(stick, inv("planks", 2)));
    }

    @Test
    void missingIntermediateIsSubCrafted() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        // 8 planks needed, none in stock: 2 plank crafts (4 each) from 2 logs, then the chest.
        assertEquals(Arrays.asList(new PlannedStep<>("planks", 2), new PlannedStep<>("chest", 1)),
                solveOk(chest, inv("log", 2)));
    }

    @Test
    void stockIsUsedBeforeSubCrafting() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        // 6 planks in stock + one craft of 4 covers the remaining 2.
        assertEquals(Arrays.asList(new PlannedStep<>("planks", 1), new PlannedStep<>("chest", 1)),
                solveOk(chest, inv("planks", 6, "log", 1)));
    }

    @Test
    void deepChainResolvesInTopologicalOrder() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        recipes.add(TestRecipe.of("stick", "stick", 4, "planks", "planks"));
        TestRecipe pickaxe = TestRecipe.of("pickaxe", "pickaxe", 1,
                "cobble", "cobble", "cobble", "stick", "stick");
        assertEquals(Arrays.asList(
                        new PlannedStep<>("planks", 1),
                        new PlannedStep<>("stick", 1),
                        new PlannedStep<>("pickaxe", 1)),
                solveOk(pickaxe, inv("cobble", 3, "log", 1)));
    }

    @Test
    void slotAlternativesFallBackToCraftableOption() {
        recipes.add(TestRecipe.of("birch_planks", "birch_planks", 4, "birch_log"));
        TestRecipe stick = TestRecipe.of("stick", "stick", 4,
                "oak_planks|birch_planks", "oak_planks|birch_planks");
        // No oak anywhere; birch is craftable, so the slot falls through to it.
        assertEquals(Arrays.asList(new PlannedStep<>("birch_planks", 1), new PlannedStep<>("stick", 1)),
                solveOk(stick, inv("birch_log", 1)));
    }

    @Test
    void stockedAlternativeBeatsCraftablePreferredOne() {
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        TestRecipe stick = TestRecipe.of("stick", "stick", 4,
                "oak_planks|birch_planks", "oak_planks|birch_planks");
        // Birch planks already in stock: no oak crafting even though oak is listed first.
        assertEquals(Arrays.asList(new PlannedStep<>("stick", 1)),
                solveOk(stick, inv("birch_planks", 2, "oak_log", 1)));
    }

    @Test
    void firstSatisfiableProducerWins() {
        recipes.add(TestRecipe.of("gem_from_ore", "gem", 1, "ore"));
        recipes.add(TestRecipe.of("gem_from_dust", "gem", 1, "dust", "dust"));
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gem");
        // No ore: the first producer fails, the second resolves.
        assertEquals(Arrays.asList(new PlannedStep<>("gem_from_dust", 1), new PlannedStep<>("ring", 1)),
                solveOk(ring, inv("dust", 2)));
    }

    // --- craftable-subset: an inventory-only alternative must never be manufactured -------------
    // (1.12.2 More Buckets: a fluid-in-NBT bucket slot lists every bucket for inventory matching,
    //  but crafting a bucket yields it empty, which the ingredient rejects — so the solver must not
    //  plan an empty-bucket sub-craft to fill a filled-bucket slot.)

    @Test
    void inventoryOnlyAlternativeIsNotSubCraftedEvenWhenItHasAProducer() {
        // A recipe that "produces" lava_bucket exists (an empty bucket keyed the same, craftable
        // from iron), and iron is in stock. Pre-fix the solver would craft the empty bucket to fill
        // the crusher's lava slot; the fix forbids it because lava_bucket is inventory-only there.
        recipes.add(TestRecipe.of("make_bucket", "lava_bucket", 1, "iron"));
        TestRecipe crusher = TestRecipe.ofInventoryOnly("crusher", "crusher", 1,
                Collections.singletonList("lava_bucket"), "lava_bucket");
        assertInstanceOf(Result.Failure.class, solver().solve(crusher, inv("iron", 1)),
                "a filled-bucket slot must not be satisfied by crafting an empty bucket");
    }

    @Test
    void inventoryOnlyAlternativeIsStillUsedFromStock() {
        // The other half: if the player actually holds the filled bucket, the plan uses it directly
        // (a single step). Inventory matching must keep the full alternative set.
        recipes.add(TestRecipe.of("make_bucket", "lava_bucket", 1, "iron"));
        TestRecipe crusher = TestRecipe.ofInventoryOnly("crusher", "crusher", 1,
                Collections.singletonList("lava_bucket"), "lava_bucket");
        assertEquals(Arrays.asList(new PlannedStep<>("crusher", 1)),
                solveOk(crusher, inv("lava_bucket", 1)));
    }

    @Test
    void craftableAlternativeAlongsideAnInventoryOnlyOneIsStillCrafted() {
        // A slot accepting a filled bucket OR a genuinely craftable item: with neither in stock, the
        // craftable one is still made (the inventory-only exclusion is per-alternative, not per-slot).
        recipes.add(TestRecipe.of("make_bucket", "lava_bucket", 1, "iron"));
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe thing = TestRecipe.ofInventoryOnly("thing", "thing", 1,
                Collections.singletonList("lava_bucket"), "lava_bucket|planks");
        assertEquals(Arrays.asList(new PlannedStep<>("planks", 1), new PlannedStep<>("thing", 1)),
                solveOk(thing, inv("log", 1)));
    }

    @Test
    void heldInventoryOnlyAlternativeIsUsedBeforeCraftingASibling() {
        // Same mixed slot, but the held item IS the inventory-only alternative: it must be consumed
        // from stock (single step), never bypassed to craft the sibling — inventory matching runs
        // over the FULL alternative set before any sub-craft is considered.
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe thing = TestRecipe.ofInventoryOnly("thing", "thing", 1,
                Collections.singletonList("lava_bucket"), "lava_bucket|planks");
        assertEquals(Arrays.asList(new PlannedStep<>("thing", 1)),
                solveOk(thing, inv("lava_bucket", 1, "log", 1)));
    }

    @Test
    void cyclicRecipesDoNotLoopForever() {
        recipes.add(TestRecipe.of("block_from_ingots", "block", 1,
                "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot"));
        recipes.add(TestRecipe.of("ingots_from_block", "ingot", 9, "block"));
        TestRecipe pick = TestRecipe.of("pick", "pick", 1, "ingot", "ingot", "ingot");
        Result<String, String> result = solver().solve(pick, inv());
        assertInstanceOf(Result.Failure.class, result);
    }

    @Test
    void compressionCycleStillUsableWhenStockExists() {
        recipes.add(TestRecipe.of("block_from_ingots", "block", 1,
                "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot"));
        recipes.add(TestRecipe.of("ingots_from_block", "ingot", 9, "block"));
        TestRecipe pick = TestRecipe.of("pick", "pick", 1, "ingot", "ingot", "ingot");
        // One block in stock: decompress once, craft the pick.
        assertEquals(Arrays.asList(new PlannedStep<>("ingots_from_block", 1), new PlannedStep<>("pick", 1)),
                solveOk(pick, inv("block", 1)));
    }

    @Test
    void remainderItemsReturnToInventory() {
        remainders.put("milk_bucket", "bucket");
        TestRecipe cake = TestRecipe.of("cake", "cake", 1,
                "milk_bucket", "milk_bucket", "milk_bucket", "wheat", "wheat", "wheat");
        List<PlannedStep<String>> steps = solveOk(cake, inv("milk_bucket", 3, "wheat", 3));
        assertEquals(Arrays.asList(new PlannedStep<>("cake", 1)), steps);
    }

    @Test
    void remainderIsNotAvailableWithinTheConsumingRecipe() {
        remainders.put("water_bucket", "bucket");
        recipes.add(TestRecipe.of("fill_bucket", "water_bucket", 1, "bucket"));
        // Both slots need water buckets but only one empty bucket exists. The remainder of
        // slot one's water bucket only reappears when the gadget itself executes — too late
        // to re-fill for slot two, since both are consumed by the same craft. Unsolvable.
        TestRecipe gadget = TestRecipe.of("gadget", "gadget", 1, "water_bucket", "water_bucket");
        assertInstanceOf(Result.Failure.class, solver().solve(gadget, inv("bucket", 1)));
    }

    @Test
    void remainderIsAvailableToStepsAfterTheConsumingCraft() {
        remainders.put("milk_bucket", "bucket");
        recipes.add(TestRecipe.of("cake", "cake", 1, "milk_bucket"));
        // The gift needs a cake and an empty bucket. Crafting the cake consumes the milk
        // bucket and frees the empty bucket as that step executes, in time for the gift.
        TestRecipe gift = TestRecipe.of("gift", "gift", 1, "cake", "bucket");
        assertEquals(Arrays.asList(new PlannedStep<>("cake", 1), new PlannedStep<>("gift", 1)),
                solveOk(gift, inv("milk_bucket", 1)));
    }

    @Test
    void hostileBranchingGraphIsCutOffByTheAttemptBudget() {
        // 4 levels of 16 interchangeable producers each: proving unsolvability would take
        // 16^4 resolveCraft calls — far over the 4096 budget. Must abort, fast, flagged.
        for (int level = 0; level < 4; level++) {
            for (int variant = 0; variant < 16; variant++) {
                recipes.add(TestRecipe.of("r" + level + "_" + variant,
                        "item" + level, 1, "item" + (level + 1)));
            }
        }
        TestRecipe target = TestRecipe.of("target", "target", 1, "item0");
        Result<String, String> result = solver().solve(target, inv());
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class, result);
        assertTrue(failure.budgetExceeded(), "expected the attempt budget to trip");
    }

    @Test
    void unsatisfiableTargetReportsMissingHint() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        Result<String, String> result = solver().solve(chest, inv("log", 1));
        // 1 log = 4 planks, 8 needed: failure mentioning planks (or its ingredient).
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class, result);
        assertTrue(failure.missing().contains("planks") || failure.missing().contains("log"),
                "missing hint should reference the shortfall, got " + failure.missing());
    }

    @Test
    void depthLimitFailsGracefully() {
        for (int i = 0; i < 50; i++) {
            recipes.add(TestRecipe.of("step" + i, "item" + i, 1, "item" + (i + 1)));
        }
        TestRecipe target = TestRecipe.of("final", "final", 1, "item0");
        Result<String, String> result = solver().solve(target, inv("item50", 1));
        // Chain of 50 exceeds maxDepth 32: must terminate with a failure, not recurse forever.
        assertInstanceOf(Result.Failure.class, result);
    }

    @Test
    void consecutiveIdenticalCraftsAreMerged() {
        recipes.add(TestRecipe.of("planks", "planks", 1, "log"));
        TestRecipe bundle = TestRecipe.of("bundle", "bundle", 1, "planks", "planks", "planks");
        assertEquals(Arrays.asList(new PlannedStep<>("planks", 3), new PlannedStep<>("bundle", 1)),
                solveOk(bundle, inv("log", 3)));
    }

    @Test
    void resultCountSurplusCarriesAcrossSlots() {
        recipes.add(TestRecipe.of("stick", "stick", 4, "planks", "planks"));
        TestRecipe frame = TestRecipe.of("frame", "frame", 1,
                "stick", "stick", "stick", "stick", "stick", "stick");
        // 6 sticks needed: two crafts of 4 (one would only give 4), planks from stock.
        assertEquals(Arrays.asList(new PlannedStep<>("stick", 2), new PlannedStep<>("frame", 1)),
                solveOk(frame, inv("planks", 4)));
    }

    // --- classification boundary: CraftPlanner keys DIRECT vs DEFERRED on steps().size()==1 ---

    @Test
    void allIngredientsInStockYieldsExactlyOneStep_theDirectClassification() {
        // CraftPlanner.classify => DIRECT iff the plan is a single step. Pin that the solver
        // emits exactly one step when nothing must be sub-crafted, so DIRECT stays DIRECT.
        TestRecipe stick = TestRecipe.of("stick", "stick", 4, "planks", "planks");
        assertEquals(1, solveOk(stick, inv("planks", 2)).size());
    }

    @Test
    void oneSubCraftYieldsTwoSteps_theDeferredClassification() {
        // The other side of the boundary: a single required intermediate makes the plan multi-step,
        // which is exactly what flips classify() to DEFERRED (the yellow-outline case).
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        assertEquals(2, solveOk(chest, inv("log", 2)).size());
    }

    @Test
    void nonAdjacentIdenticalRecipesAreNotMerged() {
        // mergeAdjacent only collapses CONSECUTIVE duplicates. CraftPreview's per-recipe totals
        // depend on the solver NOT globally deduping, so a plan of X, Y, X must stay three steps.
        // X has resultCount 1 (no surplus to reuse), and Y sits between the two X crafts. The second
        // X is demanded by a different recipe (Z), since identical slots of ONE recipe are batched.
        recipes.add(TestRecipe.of("x", "x", 1, "rawx"));
        recipes.add(TestRecipe.of("y", "y", 1, "rawy"));
        recipes.add(TestRecipe.of("z", "z", 1, "y", "x"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x", "z");
        assertEquals(Arrays.asList(
                        new PlannedStep<>("x", 1),
                        new PlannedStep<>("y", 1),
                        new PlannedStep<>("x", 1),
                        new PlannedStep<>("z", 1),
                        new PlannedStep<>("t", 1)),
                solveOk(target, inv("rawx", 2, "rawy", 1)));
    }

    @Test
    void resultCountSurplusCarriesAcrossStepsNotJustSlots() {
        // A single craft of P yields 4 "p"; two different intermediates (A, B) each consume one p.
        // The surplus from P's one craft must satisfy BOTH, so P is crafted exactly once — surplus
        // crossing a step boundary, which the within-recipe surplus test does not cover.
        recipes.add(TestRecipe.of("p", "p", 4, "rawp"));
        recipes.add(TestRecipe.of("a", "a", 1, "p"));
        recipes.add(TestRecipe.of("b", "b", 1, "p"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "a", "b");
        assertEquals(Arrays.asList(
                        new PlannedStep<>("p", 1),
                        new PlannedStep<>("a", 1),
                        new PlannedStep<>("b", 1),
                        new PlannedStep<>("t", 1)),
                solveOk(target, inv("rawp", 1)));
    }

    @Test
    void budgetExceededFlagIsFalseForAPlainShortfall() {
        // A target with no producer and nothing in stock fails on SHORTFALL, not budget — so
        // CraftPlanner reports Unsolvable, not TooComplex. Pin budgetExceeded()==false here.
        TestRecipe target = TestRecipe.of("widget", "widget", 1, "unobtanium");
        Result<String, String> result = solver().solve(target, inv());
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class, result);
        assertEquals(false, failure.budgetExceeded(), "plain shortfall must not set budgetExceeded");
    }

    @Test
    void sameSolvableGraphFailsWithBudgetExceededWhenAttemptsRunOut() {
        // Identical graph, two budgets: solvable with room, budget-failure with a tiny cap. Proves
        // budgetExceeded keys on the attempt budget (CraftPlanner's TooComplex branch), not shortfall.
        recipes.add(TestRecipe.of("x", "x", 1, "rawx"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        assertInstanceOf(Result.Success.class, solver(32, 4096).solve(target, inv("rawx", 1)));
        Result.Failure<String, String> failure =
                assertInstanceOf(Result.Failure.class, solver(32, 1).solve(target, inv("rawx", 1)));
        assertTrue(failure.budgetExceeded(), "exhausting the attempt budget must set budgetExceeded");
    }

    @Test
    void depthBoundarySucceedsAtLimitAndFailsOnDepthNotBudgetBeyondIt() {
        // Chain item0<-item1<-item2<-item3 with item3 in stock: the deepest sub-craft sits at
        // depth 3. maxDepth 3 resolves; maxDepth 2 fails — and with a generous attempt budget the
        // failure is on DEPTH, so budgetExceeded() must stay false (distinguishes the two backstops).
        recipes.add(TestRecipe.of("r0", "item0", 1, "item1"));
        recipes.add(TestRecipe.of("r1", "item1", 1, "item2"));
        recipes.add(TestRecipe.of("r2", "item2", 1, "item3"));
        TestRecipe target = TestRecipe.of("final", "final", 1, "item0");
        assertInstanceOf(Result.Success.class, solver(3, 100_000).solve(target, inv("item3", 1)));
        Result.Failure<String, String> failure =
                assertInstanceOf(Result.Failure.class, solver(2, 100_000).solve(target, inv("item3", 1)));
        assertEquals(false, failure.budgetExceeded(), "depth-limit failure must not look like a budget failure");
    }

    // --- batched quantities: deep compression trees (e.g. 9x "compressed" block mods) -----------

    /** raw -> t1 (9 raw) -> ... -> tN (9 t(N-1)); returns a target needing one tN. */
    private TestRecipe compressionChain(int levels) {
        String previous = "raw";
        for (int level = 1; level <= levels; level++) {
            String[] nine = new String[9];
            Arrays.fill(nine, previous);
            recipes.add(TestRecipe.of("t" + level, "t" + level, 1, nine));
            previous = "t" + level;
        }
        return TestRecipe.of("target", "target", 1, previous);
    }

    @Test
    void deepCompressionChainPlansWithAmpleStock() {
        // 9^5 raw covers one t5 exactly. Thousands of base crafts must not count against the
        // attempt budget one unit at a time: the plan is one batched step per level.
        TestRecipe target = compressionChain(5);
        assertEquals(Arrays.asList(
                        new PlannedStep<>("t1", 6561),
                        new PlannedStep<>("t2", 729),
                        new PlannedStep<>("t3", 81),
                        new PlannedStep<>("t4", 9),
                        new PlannedStep<>("t5", 1),
                        new PlannedStep<>("target", 1)),
                solveOk(target, inv("raw", 59049)));
    }

    @Test
    void deepChainShortfallReportsExactRawCount() {
        // 9^12 raw exceeds int: the long view is exact, contains only the raw leaf, and the legacy
        // int view clamps instead of wrapping.
        TestRecipe target = compressionChain(12);
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(target, inv());
        Map<String, Long> expected = new HashMap<>();
        expected.put("raw", 282_429_536_481L);
        assertEquals(expected, r.demandsLong());
        assertFalse(r.approximate(), "a 12-level chain is well within depth and budget");
        assertFalse(r.saturated());
        assertEquals(inv("raw", Integer.MAX_VALUE), r.demands());
    }

    @Test
    void shortfallBeyondLongRangeSaturates() {
        // 9^21 > Long.MAX_VALUE: the count pins at the ceiling and says so, never wraps negative.
        TestRecipe target = compressionChain(21);
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(target, inv());
        Map<String, Long> expected = new HashMap<>();
        expected.put("raw", Long.MAX_VALUE);
        assertEquals(expected, r.demandsLong());
        assertTrue(r.saturated(), "a count at Long.MAX_VALUE must set saturated");
    }

    @Test
    void batchedSlotMixesSourcesAcrossAlternatives() {
        // 8 planks of either kind, one oak log and one birch log: neither log alone covers the batch,
        // so the planner must split it (4 oak + 4 birch) instead of failing the whole batch on oak.
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        recipes.add(TestRecipe.of("birch_planks", "birch_planks", 4, "birch_log"));
        String[] eight = new String[8];
        Arrays.fill(eight, "oak_planks|birch_planks");
        TestRecipe chest = TestRecipe.of("chest", "chest", 1, eight);
        assertEquals(Arrays.asList(
                        new PlannedStep<>("oak_planks", 1),
                        new PlannedStep<>("birch_planks", 1),
                        new PlannedStep<>("chest", 1)),
                solveOk(chest, inv("oak_log", 1, "birch_log", 1)));
        // The gather list agrees: everything needed is already held.
        assertEquals(Collections.emptyMap(),
                solver().shortfall(chest, inv("oak_log", 1, "birch_log", 1)).demands());
    }

    @Test
    void nonAdjacentSlotsKeepTheirOwnOrderOverSharedStock() {
        // Slots 1 and 3 accept either plank, slot 2 only oak. Taken in slot order (oak, oak, birch)
        // two of each suffice; batching slots 1 and 3 together first would drain the oak slot 2 needs.
        TestRecipe sandwich = TestRecipe.of("sandwich", "sandwich", 1,
                "oak_planks|birch_planks", "oak_planks", "oak_planks|birch_planks");
        Map<String, Integer> held = inv("oak_planks", 2, "birch_planks", 2);
        assertEquals(Arrays.asList(new PlannedStep<>("sandwich", 1)), solveOk(sandwich, held));
        assertEquals(Collections.emptyMap(), solver().shortfall(sandwich, held).demands());
    }

    @Test
    void gatheringTheShortfallMakesTheRecipeSolvable() {
        // i1's first recipe wants r1-or-r0 plus r3s; its second only r3 (held). Crediting the r3-only
        // route is wrong once r0 is gathered: the solver then takes the FIRST recipe, which eats the
        // r0 the target's own slot needed. The list must be enough for the planner, not just the walk.
        recipes.add(TestRecipe.of("i1_0", "i1", 2, "r1|r0", "r3", "r3|r1"));
        recipes.add(TestRecipe.of("i1_1", "i1", 3, "r3", "r3"));
        TestRecipe target = TestRecipe.of("t", "t", 2, "i1", "i1", "r0|r1");
        Map<String, Integer> held = inv("r3", 4);
        Map<String, Integer> demands = solver().shortfall(target, held).demands();
        Map<String, Integer> gathered = new HashMap<>(held);
        demands.forEach((item, count) -> gathered.merge(item, count, Integer::sum));
        assertInstanceOf(Result.Success.class, solver().solve(target, gathered),
                "gathering " + demands + " must make the recipe craftable");
    }

    // --- shortfall: net raw-gatherable demand for a red/unmakeable recipe ---------------------

    @Test
    void shortfallOfFullyStockedRecipeIsEmpty() {
        TestRecipe stick = TestRecipe.of("stick", "stick", 4, "planks", "planks");
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(stick, inv("planks", 2));
        assertEquals(Collections.emptyMap(), r.demands());
        assertFalse(r.approximate());
    }

    @Test
    void shortfallOfPlainLeafTargetReportsTheLeaf() {
        // No producer for the only ingredient and nothing in stock: it IS the gatherable.
        TestRecipe widget = TestRecipe.of("widget", "widget", 1, "unobtanium");
        assertEquals(inv("unobtanium", 1), solver().shortfall(widget, inv()).demands());
    }

    @Test
    void shortfallCollapsesMultiLevelChainToRaws() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        // 8 planks, 4 per log → 2 logs to gather; planks themselves are not listed (craftable).
        assertEquals(inv("log", 2), solver().shortfall(chest, inv()).demands());
    }

    @Test
    void shortfallCreditsPartialInventory() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        // 6 planks already held; one more log craft (yields 4) covers the remaining 2.
        assertEquals(inv("log", 1), solver().shortfall(chest, inv("planks", 6)).demands());
    }

    @Test
    void shortfallCreditsOverproductionAcrossSteps() {
        // One craft of "stick" yields 4; a and b each consume one. The surplus must cover both,
        // so only one rawstick is gathered, not two.
        recipes.add(TestRecipe.of("stick", "stick", 4, "rawstick"));
        recipes.add(TestRecipe.of("a", "a", 1, "stick"));
        recipes.add(TestRecipe.of("b", "b", 1, "stick"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "a", "b");
        assertEquals(inv("rawstick", 1), solver().shortfall(target, inv()).demands());
    }

    @Test
    void shortfallPrefersStockedAlternative() {
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        TestRecipe stick = TestRecipe.of("stick", "stick", 4,
                "oak_planks|birch_planks", "oak_planks|birch_planks");
        // Birch planks in stock satisfy both slots; nothing to gather even though oak is listed first.
        assertEquals(Collections.emptyMap(), solver().shortfall(stick, inv("birch_planks", 2)).demands());
    }

    @Test
    void shortfallFallsThroughToCraftableAlternative() {
        // Neither alternative is a raw leaf (both planks are craftable from their logs), so the slot
        // falls through to crafting the first listed and reporting ITS raw log.
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        recipes.add(TestRecipe.of("birch_planks", "birch_planks", 4, "birch_log"));
        TestRecipe stick = TestRecipe.of("stick", "stick", 4,
                "oak_planks|birch_planks", "oak_planks|birch_planks");
        assertEquals(inv("oak_log", 1), solver().shortfall(stick, inv()).demands());
    }

    @Test
    void shortfallCapturesSlotAlternativesForCycling() {
        // Each missing slot remembers its full accepted set so the UI can cycle them. Both items in
        // each slot here are raw leaves, so the chosen representative is the first, keyed in demands.
        TestRecipe thing = TestRecipe.of("thing", "thing", 1, "coal|charcoal", "oak_log|spruce_log");
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(thing, inv());
        assertEquals(Arrays.asList("coal", "charcoal"), r.alternatives().get("coal"));
        assertEquals(Arrays.asList("oak_log", "spruce_log"), r.alternatives().get("oak_log"));
    }

    @Test
    void shortfallUsesStockedWoodForAlternativeSlots() {
        // A stick's planks slot accepts oak OR spruce planks; the player has a spruce log. The walk
        // must craft via spruce (the wood it can make from stock), not demand the first-listed oak log.
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        recipes.add(TestRecipe.of("spruce_planks", "spruce_planks", 4, "spruce_log"));
        recipes.add(TestRecipe.of("stick", "stick", 4,
                "oak_planks|spruce_planks", "oak_planks|spruce_planks"));
        TestRecipe rod = TestRecipe.of("rod", "rod", 1, "stick");
        assertEquals(Collections.emptyMap(), solver().shortfall(rod, inv("spruce_log", 1)).demands());
    }

    @Test
    void shortfallPrefersRawLeafOverCraftableAlternative() {
        // A #logs-style slot accepting the raw oak_log OR the craftable oak_wood (4 logs → 3 wood):
        // gather the raw log, never route through the wood block (which would demand four logs).
        recipes.add(TestRecipe.of("oak_wood", "oak_wood", 3, "oak_log", "oak_log", "oak_log", "oak_log"));
        TestRecipe thing = TestRecipe.of("thing", "thing", 1, "oak_log|oak_wood");
        assertEquals(inv("oak_log", 1), solver().shortfall(thing, inv()).demands());
    }

    @Test
    void shortfallBreaksCompressionCycleAndTerminates() {
        recipes.add(TestRecipe.of("block_from_ingots", "block", 1,
                "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot"));
        recipes.add(TestRecipe.of("ingots_from_block", "ingot", 9, "block"));
        TestRecipe pick = TestRecipe.of("pick", "pick", 1, "ingot", "ingot", "ingot");
        // ingot ⇄ block is the only producer path: the cycle break bottoms out at ingot itself.
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(pick, inv());
        assertFalse(r.demands().isEmpty());
        assertTrue(r.demands().containsKey("ingot"), "cycle should bottom out at the cycle item");
    }

    @Test
    void shortfallStopsAtBudgetAndReturnsPartial() {
        // Six DIFFERENT planks: identical slots are batched into one node, distinct ones are not.
        for (int i = 0; i < 6; i++) {
            recipes.add(TestRecipe.of("plank" + i, "plank" + i, 1, "log" + i));
        }
        TestRecipe wall = TestRecipe.of("wall", "wall", 1,
                "plank0", "plank1", "plank2", "plank3", "plank4", "plank5");
        // Tiny budget can't walk all six plank crafts: returns a partial map, flagged approximate.
        RecipeGraphSolver.ShortfallResult<String> r = solver(32, 3).shortfall(wall, inv());
        assertTrue(r.approximate(), "exhausting the budget must flag the result approximate");
        assertFalse(r.demands().isEmpty());
    }

    @Test
    void shortfallIgnoresCraftingRemainders() {
        remainders.put("milk_bucket", "bucket");
        TestRecipe cake = TestRecipe.of("cake", "cake", 1,
                "milk_bucket", "milk_bucket", "milk_bucket", "wheat", "wheat", "wheat");
        Map<String, Integer> demands = solver().shortfall(cake, inv()).demands();
        assertEquals(inv("milk_bucket", 3, "wheat", 3), demands);
        assertFalse(demands.containsKey("bucket"), "a byproduct is never a gatherable input");
    }

    @Test
    void shortfallDoesNotInflateThroughStorageDecompression() {
        // coal's only producer is "uncrafting" a coal_block (yields 9), and the block is made from
        // 9 coal. Needing 1 coal must report 1 coal to gather — NOT the 9 that filling a block implies.
        recipes.add(TestRecipe.of("coal_from_block", "coal", 9, "coal_block"));
        recipes.add(TestRecipe.of("block_from_coal", "coal_block", 1,
                "coal", "coal", "coal", "coal", "coal", "coal", "coal", "coal", "coal"));
        TestRecipe torch = TestRecipe.of("torch", "torch", 4, "coal", "stick");
        assertEquals(inv("coal", 1, "stick", 1), solver().shortfall(torch, inv()).demands());
    }

    @Test
    void shortfallOfCoalTagSlotNeedsExactlyOne() {
        // The campfire-style "coal OR charcoal" slot: coal has only a decompression producer, charcoal
        // is a pure leaf. Either alternative, the answer is one unit to gather — never nine.
        recipes.add(TestRecipe.of("coal_from_block", "coal", 9, "coal_block"));
        recipes.add(TestRecipe.of("block_from_coal", "coal_block", 1,
                "coal", "coal", "coal", "coal", "coal", "coal", "coal", "coal", "coal"));
        TestRecipe campfire = TestRecipe.of("campfire", "campfire", 1, "coal|charcoal");
        Map<String, Integer> demands = solver().shortfall(campfire, inv()).demands();
        assertEquals(1, demands.values().stream().mapToInt(Integer::intValue).sum(),
                "exactly one coal-or-charcoal to gather, got " + demands);
    }

    // --- executability: every returned plan must replay the way the executors run it ----------
    // (issues #10, #18: the solver's model of which stock a slot consumes must be the one the
    //  executors follow, SlotAssignment, one craft at a time)

    /**
     * Replays {@code steps} one craft at a time, each craft's slots filled by
     * {@link SlotAssignment#assign} over the current stock, crediting the result and remainders after
     * each craft: what an executor that follows the shared rule does. Deliberately independent of
     * {@link PlanSimulator}'s batching. Returns null when every slot of every craft was filled,
     * otherwise where it came up short.
     */
    private String replayCraftByCraft(TestRecipe target, List<PlannedStep<String>> steps,
                                      Map<String, Integer> inventory) {
        Map<String, Integer> stock = new HashMap<>(inventory);
        for (int i = 0; i < steps.size(); i++) {
            PlannedStep<String> step = steps.get(i);
            TestRecipe recipe = byId(target, step.recipeId());
            if (recipe == null) {
                return "step " + i + ": unknown recipe " + step.recipeId();
            }
            List<List<String>> slots = new ArrayList<>();
            for (List<String> slot : recipe.ingredientSlots()) {
                if (!slot.isEmpty()) {
                    slots.add(slot);
                }
            }
            for (int craft = 0; craft < step.crafts(); craft++) {
                List<String> chosen = SlotAssignment.assign(slots, stock);
                for (int s = 0; s < slots.size(); s++) {
                    if (chosen.get(s) == null) {
                        return "step " + i + " (" + step.recipeId() + ") craft " + craft + ": slot "
                                + slots.get(s) + " found nothing in " + stock + "; plan " + steps;
                    }
                }
                for (String item : chosen) {
                    stock.merge(item, -1, Integer::sum);
                }
                stock.merge(recipe.result(), recipe.resultCount(), Integer::sum);
                for (String item : chosen) {
                    String leftBehind = remainders.get(item);
                    if (leftBehind != null) {
                        stock.merge(leftBehind, 1, Integer::sum);
                    }
                }
            }
        }
        return null;
    }

    private TestRecipe byId(TestRecipe target, String id) {
        if (target.id().equals(id)) {
            return target;
        }
        for (TestRecipe recipe : recipes) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        return null;
    }

    private PlanSimulator.Replay<String, String> simulate(TestRecipe target, List<PlannedStep<String>> steps,
                                                          Map<String, Integer> inventory) {
        return PlanSimulator.replay(steps, id -> byId(target, id), remainders::get, inventory);
    }

    /** solve() succeeds and its plan replays, both through PlanSimulator and craft by craft. */
    private void assertExecutable(TestRecipe target, Map<String, Integer> inventory) {
        List<PlannedStep<String>> steps = solveOk(target, inventory);
        PlanSimulator.Replay<String, String> replay = simulate(target, steps, inventory);
        assertTrue(replay.ok(), "plan " + steps + " does not replay: " + replay);
        String shortAt = replayCraftByCraft(target, steps, inventory);
        assertEquals(null, shortAt, "plan " + steps + " does not replay craft by craft");
    }

    @Test
    void subCraftDoesNotEatStockALaterStepCountedOn() {
        // #10: m2_1 counts on the four r3 for its [r3|m0] slots, but the m0_0 crafts that feed its
        // [m0|r0] slots run first and, at execution, take r3 before r1.
        recipes.add(TestRecipe.of("m0_0", "m0", 3, "r3|r1"));
        recipes.add(TestRecipe.of("m0_1", "m0", 9, "r0", "r0", "r0", "r0"));
        recipes.add(TestRecipe.of("m1_0", "m1", 1, "r2", "r2", "r2", "r2"));
        recipes.add(TestRecipe.of("m2_0", "m2", 2, "r0", "m1", "m1", "m1"));
        recipes.add(TestRecipe.of("m2_1", "m2", 1, "m0|r0", "m0|r0", "r3|m0", "r3|m0"));
        TestRecipe target = TestRecipe.of("TARGET", "TARGET", 1, "m2", "m2", "m2", "m2");
        assertExecutable(target, inv("r2", 10, "r3", 4, "r1", 10));
    }

    @Test
    void earlierSlotDoesNotEatTheSubCraftALaterSlotNeeds() {
        // #10 dual: the c crafted for the [c] slot appears before the target executes, where the
        // earlier [c|f] and [c|g|b] slots must leave it alone.
        recipes.add(TestRecipe.of("r1", "c", 1, "d"));
        TestRecipe target = TestRecipe.of("r0", "t", 1, "d", "c|f", "c|g|b", "c");
        assertExecutable(target, inv("c", 1, "d", 6, "f", 3, "g", 4));
    }

    @Test
    void flexibleSlotLeavesTheOnlyItemARigidSlotAccepts() {
        // #18: the oak-or-birch slot must take the birch, so the oak-only slot gets the oak.
        TestRecipe target = TestRecipe.of("t", "t", 1, "oak|birch", "oak");
        assertExecutable(target, inv("oak", 1, "birch", 1));
    }

    @Test
    void resultAlsoAcceptedBySameRecipeSlotDoesNotInflateStock() {
        // Each craft of r0 after the first eats the c the craft before it made (its [c|d|f] slot takes
        // c), so three crafts leave one c, not three. A batched replay that books consumption and
        // credit separately, clamping in between, would count three and accept a plan that shorts.
        recipes.add(TestRecipe.of("r0", "c", 1, "c|d|f", "b", "b|f"));
        remainders.put("b", "f");
        TestRecipe target = TestRecipe.of("TARGET", "TARGET", 1, "c|f", "c", "b", "c");
        Map<String, Integer> held = inv("d", 4, "b", 11);
        List<PlannedStep<String>> broken = Arrays.asList(new PlannedStep<>("r0", 3), new PlannedStep<>("TARGET", 1));
        assertFalse(replayCraftByCraft(target, broken, held) == null, "premise: " + broken + " shorts craft by craft");
        assertFalse(simulate(target, broken, held).ok(), "PlanSimulator accepts " + broken + ", which shorts");
        Result<String, String> result = solver().solve(target, held);
        if (result instanceof Result.Success) {
            List<PlannedStep<String>> steps = ((Result.Success<String, String>) result).steps();
            assertEquals(null, replayCraftByCraft(target, steps, held), "plan " + steps + " shorts craft by craft");
        }
    }

    @Test
    void batchedReplayMatchesCraftByCraft() {
        // PlanSimulator applies runs of identical crafts in one go; that must leave exactly the stock
        // (and the same shortage point) as crafting one at a time, including when a craft consumes an
        // item it is also credited (its own result in a slot, or a remainder equal to an alternative).
        java.util.Random random = new java.util.Random(0x5C1A7C4EL);
        String[] items = {"a", "b", "c", "d", "e"};
        for (int trial = 0; trial < 20000; trial++) {
            remainders.clear();
            for (int r = random.nextInt(3); r > 0; r--) {
                remainders.put(items[random.nextInt(items.length)], items[random.nextInt(items.length)]);
            }
            String[] slots = new String[1 + random.nextInt(4)];
            for (int s = 0; s < slots.length; s++) {
                List<String> pool = new ArrayList<>(Arrays.asList(items));
                Collections.shuffle(pool, random);
                slots[s] = String.join("|", pool.subList(0, 1 + random.nextInt(3)));
            }
            TestRecipe recipe = TestRecipe.of("r", items[random.nextInt(items.length)], 1 + random.nextInt(3), slots);
            Map<String, Integer> stock = new HashMap<>();
            Map<String, Long> start = new HashMap<>();
            for (String item : items) {
                int count = random.nextInt(7);
                if (count > 0) {
                    stock.put(item, count);
                    start.put(item, (long) count);
                }
            }
            int crafts = 1 + random.nextInt(12);
            String where = "trial " + trial + ": " + recipe.ingredientSlots() + " -> " + recipe.resultCount() + " "
                    + recipe.result() + " x" + crafts + ", stock " + stock + ", remainders " + remainders;

            UndoMap<String, Long> batched = new UndoMap<>(start);
            PlanSimulator.Batch<String> batch = PlanSimulator.run(recipe, crafts, batched, remainders::get, true);

            boolean shorted = false;
            int completed = 0;
            for (; completed < crafts; completed++) {
                List<String> chosen = SlotAssignment.assign(recipe.ingredientSlots(), stock);
                if (chosen.contains(null)) {
                    shorted = true;
                    break;
                }
                for (String item : chosen) {
                    stock.merge(item, -1, Integer::sum);
                }
                stock.merge(recipe.result(), recipe.resultCount(), Integer::sum);
                for (String item : chosen) {
                    String leftBehind = remainders.get(item);
                    if (leftBehind != null) {
                        stock.merge(leftBehind, 1, Integer::sum);
                    }
                }
            }
            assertEquals(shorted, batch.isShort(), where);
            assertEquals(completed, batch.completeBeforeShort, where);
            for (String item : items) {
                Long have = batched.get(item);
                assertEquals((long) stock.getOrDefault(item, 0), have == null ? 0L : have, where + ": count of " + item);
            }

            // Without stopping at the first short craft (the exact pass's mode): a short craft
            // consumes what it could assign and is credited nothing.
            UndoMap<String, Long> through = new UndoMap<>(start);
            PlanSimulator.Batch<String> all = PlanSimulator.run(recipe, crafts, through, remainders::get, false);
            Map<String, Integer> slow = new HashMap<>();
            for (Map.Entry<String, Long> entry : start.entrySet()) {
                slow.put(entry.getKey(), entry.getValue().intValue());
            }
            long[] shortage = new long[recipe.ingredientSlots().size()];
            for (int craft = 0; craft < crafts; craft++) {
                List<String> chosen = SlotAssignment.assign(recipe.ingredientSlots(), slow);
                for (int s = 0; s < chosen.size(); s++) {
                    if (chosen.get(s) == null) {
                        shortage[s]++;
                    } else {
                        slow.merge(chosen.get(s), -1, Integer::sum);
                    }
                }
                if (chosen.contains(null)) {
                    continue;
                }
                slow.merge(recipe.result(), recipe.resultCount(), Integer::sum);
                for (String item : chosen) {
                    String leftBehind = remainders.get(item);
                    if (leftBehind != null) {
                        slow.merge(leftBehind, 1, Integer::sum);
                    }
                }
            }
            assertTrue(Arrays.equals(shortage, all.shortage), where + ": per-slot shortage "
                    + Arrays.toString(all.shortage) + ", craft by craft " + Arrays.toString(shortage));
            for (String item : items) {
                Long have = through.get(item);
                assertEquals((long) slow.getOrDefault(item, 0), have == null ? 0L : have,
                        where + ": count of " + item + " when not stopping at a short craft");
            }
        }
    }

    @Test
    void randomGraphsOnlyYieldExecutablePlans() {
        // The property review_final fuzzed: whatever solve() returns replays without a shortage when
        // an executor follows SlotAssignment craft by craft. Also checks PlanSimulator's batched replay
        // agrees with the craft-by-craft one on those plans and on perturbed (often broken) ones.
        java.util.Random random = new java.util.Random(0x5C100018L);
        String[] raws = {"r0", "r1", "r2", "r3"};
        String[] mids = {"m0", "m1", "m2"};
        int[] yields = {1, 2, 3, 4, 9};
        int trials = 3000;
        int solved = 0;
        for (int trial = 0; trial < trials; trial++) {
            recipes.clear();
            remainders.clear();
            if (random.nextInt(10) == 0) {
                // Sometimes a mid as the remainder, so a craft can be credited what it also consumes.
                String[] pool = random.nextBoolean() ? raws : mids;
                remainders.put(raws[random.nextInt(4)], pool[random.nextInt(pool.length)]);
            }
            for (int m = 0; m < mids.length; m++) {
                int variants = 1 + random.nextInt(2);
                for (int v = 0; v < variants; v++) {
                    String[] slots = new String[1 + random.nextInt(4)];
                    for (int s = 0; s < slots.length; s++) {
                        // One slot in eight may also accept the recipe's own result (a cycle).
                        slots[s] = randomSlot(random, raws, mids, random.nextInt(8) == 0 ? m + 1 : m);
                    }
                    recipes.add(TestRecipe.of(mids[m] + "_" + v, mids[m],
                            yields[random.nextInt(yields.length)], slots));
                }
            }
            String[] targetSlots = new String[1 + random.nextInt(4)];
            for (int s = 0; s < targetSlots.length; s++) {
                targetSlots[s] = randomSlot(random, raws, mids, mids.length);
            }
            TestRecipe target = TestRecipe.of("TARGET", "TARGET", 1, targetSlots);
            Map<String, Integer> held = new HashMap<>();
            for (String raw : raws) {
                if (random.nextInt(3) > 0) {
                    held.put(raw, random.nextInt(11));
                }
            }
            if (random.nextInt(4) == 0) {
                held.put(mids[random.nextInt(mids.length)], 1 + random.nextInt(4));
            }
            Result<String, String> result = solver().solve(target, held);
            if (!(result instanceof Result.Success)) {
                continue;
            }
            solved++;
            List<PlannedStep<String>> steps = ((Result.Success<String, String>) result).steps();
            String shortAt = replayCraftByCraft(target, steps, held);
            assertEquals(null, shortAt, "trial " + trial + ": target " + target.ingredientSlots()
                    + ", recipes " + describe(recipes) + ", held " + held + ", remainders " + remainders);
            assertTrue(simulate(target, steps, held).ok(), "trial " + trial + ": PlanSimulator rejects " + steps);
            // Perturb: one step's crafts doubled. Batched and craft-by-craft replay must agree.
            List<PlannedStep<String>> perturbed = new ArrayList<>(steps);
            int at = random.nextInt(perturbed.size());
            perturbed.set(at, perturbed.get(at).withCrafts(perturbed.get(at).crafts() * 2));
            assertEquals(replayCraftByCraft(target, perturbed, held) == null, simulate(target, perturbed, held).ok(),
                    "trial " + trial + ": batched and craft-by-craft replay disagree on " + perturbed);
        }
        // Guard against a generator that never produces a solvable graph (the property would be vacuous).
        assertTrue(solved >= trials / 10, "only " + solved + " of " + trials + " random graphs were solvable");
    }

    private static String randomSlot(java.util.Random random, String[] raws, String[] mids, int tier) {
        int alternatives = 1 + random.nextInt(3);
        List<String> pool = new ArrayList<>(Arrays.asList(raws));
        for (int m = 0; m < tier; m++) {
            pool.add(mids[m]);
        }
        Collections.shuffle(pool, random);
        return String.join("|", pool.subList(0, Math.min(alternatives, pool.size())));
    }

    private static String describe(List<TestRecipe> list) {
        StringBuilder out = new StringBuilder();
        for (TestRecipe recipe : list) {
            out.append(recipe.id()).append(recipe.ingredientSlots()).append("->")
                    .append(recipe.resultCount()).append(' ').append(recipe.result()).append("; ");
        }
        return out.toString();
    }

    private static String[] nine(String item) {
        String[] slots = new String[9];
        Arrays.fill(slots, item);
        return slots;
    }

    @Test
    void shortfallExpandsTwoCycleMemberThroughItsRecipeFromRaws() {
        // Issue #11 (Infinity Matter Dominator Sword: Reborn): crystal <-> crystal_block is a storage
        // 2-cycle, but crystal ALSO has a recipe from raws. The uncraft is listed first (as
        // CraftPlanner's id sort puts it), which used to make the walk stop at crystal and
        // crystal_block; it must reach diamond and gold.
        recipes.add(TestRecipe.of("crystal_from_block", "crystal", 9, "crystal_block"));
        recipes.add(TestRecipe.of("crystal_recipe", "crystal", 1,
                "diamond", "diamond", "diamond", "diamond", "gold", "gold", "gold", "gold"));
        recipes.add(TestRecipe.of("crystal_block_recipe", "crystal_block", 1, nine("crystal")));
        TestRecipe sword = TestRecipe.of("sword", "sword", 1, "crystal", "crystal", "crystal_block");
        // 2 crystal + 9 for the block = 11 crystal = 44 diamond + 44 gold.
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(sword, inv());
        assertEquals(inv("diamond", 44, "gold", 44), r.demands());
        assertFalse(r.approximate());
        assertInstanceOf(Result.Success.class, solver().solve(sword, inv("diamond", 44, "gold", 44)));
    }

    @Test
    void shortfallExpandsThreeCycleMemberThroughItsRecipeFromRaws() {
        // block <-> ingot <-> nugget, with ingot also made from a raw (listed last). Every member's
        // demand must bottom out at the raw: 9 (block) + 1 (nugget, 8 spare) + 1 (ingot) = 11.
        recipes.add(TestRecipe.of("ingot_from_block", "ingot", 9, "block"));
        recipes.add(TestRecipe.of("ingot_from_nuggets", "ingot", 1, nine("nugget")));
        recipes.add(TestRecipe.of("ingot_from_raw", "ingot", 1, "raw_ore"));
        recipes.add(TestRecipe.of("nugget_from_ingot", "nugget", 9, "ingot"));
        recipes.add(TestRecipe.of("block_from_ingots", "block", 1, nine("ingot")));
        TestRecipe thing = TestRecipe.of("thing", "thing", 1, "block", "nugget", "ingot");
        RecipeGraphSolver.ShortfallResult<String> r = solver().shortfall(thing, inv());
        assertEquals(inv("raw_ore", 11), r.demands());
        assertFalse(r.approximate());
    }

    @Test
    void shortfallOfThreeCycleWithoutRawRouteListsTheRequestedMember() {
        // Same 3-cycle with no way out: nothing is gatherable below the cycle, so each demand is
        // listed as the item asked for (1 block, 1 nugget), never inflated to 9 ingots / 81 nuggets.
        recipes.add(TestRecipe.of("ingot_from_block", "ingot", 9, "block"));
        recipes.add(TestRecipe.of("ingot_from_nuggets", "ingot", 1, nine("nugget")));
        recipes.add(TestRecipe.of("nugget_from_ingot", "nugget", 9, "ingot"));
        recipes.add(TestRecipe.of("block_from_ingots", "block", 1, nine("ingot")));
        TestRecipe thing = TestRecipe.of("thing", "thing", 1, "block", "nugget");
        assertEquals(inv("block", 1, "nugget", 1), solver().shortfall(thing, inv()).demands());
    }

    @Test
    void shortfallDiscardedCycleBranchDoesNotFlagExactListApproximate() {
        // x's first recipe needs y (a chain deeper than maxDepth, so that branch hits the depth
        // limit) and c, which loops back to x: that whole branch is undone and x comes from r. The
        // depth hit lived only in the discarded branch, so the list is exact and must not be flagged
        // (an approximate flag also skips the feasibility check).
        recipes.add(TestRecipe.of("x_via_cycle", "x", 1, "y", "c"));
        recipes.add(TestRecipe.of("y_from_z", "y", 1, "z"));
        recipes.add(TestRecipe.of("z_from_w", "z", 1, "w"));
        recipes.add(TestRecipe.of("w_from_v", "w", 1, "v"));
        recipes.add(TestRecipe.of("c_from_x", "c", 1, "x"));
        recipes.add(TestRecipe.of("x_from_r", "x", 1, "r"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        RecipeGraphSolver.ShortfallResult<String> r = solver(2, 4096).shortfall(target, inv());
        assertEquals(inv("r", 1), r.demands());
        assertFalse(r.approximate(), "the depth hit was in an undone branch");
    }
}
