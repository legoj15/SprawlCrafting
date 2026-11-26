package com.legoj15.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.legoj15.solver.RecipeGraphSolver.RecipeInfo;
import com.legoj15.solver.RecipeGraphSolver.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGraphSolverTest {

    /**
     * String-typed recipe for tests: id, slots (each "a|b" = alternatives), result, count. A plain
     * recipe leaves {@link #station()} and {@link #ingredientSlotCount} to the interface defaults;
     * {@link #withStation}, {@link #withSlotCounts} and {@link #withExplicitDefaults} override them.
     */
    private static final class TestRecipe implements RecipeInfo<String, String> {
        private final String id;
        private final List<List<String>> ingredientSlots;
        private final List<List<String>> craftableIngredientSlots;
        private final String result;
        private final int resultCount;
        /** Whether this recipe answers {@link #station()} itself (null included) instead of the default. */
        private final boolean stationDeclared;
        private final String station;
        /** Per slot quantity, or null to leave {@link #ingredientSlotCount} at the interface default. */
        private final int[] slotCounts;

        TestRecipe(String id, List<List<String>> ingredientSlots,
                   List<List<String>> craftableIngredientSlots, String result, int resultCount) {
            this(id, ingredientSlots, craftableIngredientSlots, result, resultCount, false, null, null);
        }

        private TestRecipe(String id, List<List<String>> ingredientSlots,
                           List<List<String>> craftableIngredientSlots, String result, int resultCount,
                           boolean stationDeclared, String station, int[] slotCounts) {
            this.id = id;
            this.ingredientSlots = ingredientSlots;
            this.craftableIngredientSlots = craftableIngredientSlots;
            this.result = result;
            this.resultCount = resultCount;
            this.stationDeclared = stationDeclared;
            this.station = station;
            this.slotCounts = slotCounts;
        }

        /** This recipe running at {@code station} (an item that stays in stock). */
        TestRecipe withStation(String station) {
            return new TestRecipe(id, ingredientSlots, craftableIngredientSlots, result, resultCount,
                    true, station, slotCounts);
        }

        /** This recipe with per-slot quantities (one per entry of {@link #ingredientSlots()}). */
        TestRecipe withSlotCounts(int... counts) {
            return new TestRecipe(id, ingredientSlots, craftableIngredientSlots, result, resultCount,
                    stationDeclared, station, counts.clone());
        }

        /**
         * This recipe answering every new method itself with the value the interface would have
         * returned (no station, quantity 1 per slot), so a run through it must equal a run through
         * the defaults.
         */
        TestRecipe withExplicitDefaults() {
            int[] counts = slotCounts;
            if (counts == null) {
                counts = new int[ingredientSlots.size()];
                Arrays.fill(counts, 1);
            }
            return new TestRecipe(id, ingredientSlots, craftableIngredientSlots, result, resultCount,
                    true, station, counts);
        }

        @Override
        public String station() {
            return stationDeclared ? station : RecipeInfo.super.station();
        }

        @Override
        public int ingredientSlotCount(int slot) {
            return slotCounts != null ? slotCounts[slot] : RecipeInfo.super.ingredientSlotCount(slot);
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

    private static Map<String, Long> invLong(Object... pairs) {
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Long) pairs[i + 1]);
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
    void saturatedStockCountIsUnbounded() {
        // An int stock count at Integer.MAX_VALUE means "at least that many" (an AE2 creative cell or a
        // Sophisticated Storage infinity upgrade reports exactly this and delivers any amount), so a
        // chain needing 9^10 raw (> int) must plan, and the gather list must be empty.
        TestRecipe target = compressionChain(10);
        solveOk(target, inv("raw", Integer.MAX_VALUE));
        assertTrue(solver().shortfall(target, inv("raw", Integer.MAX_VALUE)).demandsLong().isEmpty());
    }

    @Test
    void finiteStockAboveAnIntIsNotUnbounded() {
        // #47: through the long entry points only Long.MAX_VALUE means unbounded, so a REAL count above
        // Integer.MAX_VALUE (billions of leftover ingots in an ME network) is that finite count: three of
        // the four billion a's one target needs is a shortfall naming a (not a budget failure), five
        // billion (also over an int) plans, and the sentinel still plans.
        String previous = "a";
        for (int level = 1; level <= 9; level++) {
            String[] ten = new String[10];
            Arrays.fill(ten, previous);
            recipes.add(TestRecipe.of("x" + level, "x" + level, 1, ten));
            previous = "x" + level;
        }
        // Four x9 slots, each level ten of the one below: one target craft needs 4 * 10^9 of a.
        TestRecipe target = TestRecipe.of("t", "t", 1, "x9", "x9", "x9", "x9");
        Result<String, String> result = solver().solveIntSizedLong(target, invLong("a", 3_000_000_000L));
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class, result);
        assertFalse(failure.budgetExceeded(), "a real shortfall, not a tree too complex to plan");
        assertTrue(failure.missing().contains("a"), "the shortfall must be named, got " + failure.missing());
        assertInstanceOf(Result.Success.class,
                solver().solveIntSizedLong(target, invLong("a", 5_000_000_000L)));
        assertInstanceOf(Result.Success.class,
                solver().solveIntSizedLong(target, invLong("a", Long.MAX_VALUE)));
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

    // --- executability: every returned plan must replay under its own layouts -----------------
    // (issues #10, #18, #23: the solver fixes which item each craft's every slot takes — the
    //  executors follow those layouts, so a sub-craft can no longer eat the only copy a later step
    //  needs. SlotAssignment remains the fallback when the inventory no longer matches.)

    /**
     * The recipe's non-empty slots with each repeated by its quantity: the slot list a plan's layouts
     * and a replay are indexed by.
     */
    private static List<List<String>> expandedSlots(TestRecipe recipe) {
        List<List<String>> slots = new ArrayList<>();
        for (int s = 0; s < recipe.ingredientSlots().size(); s++) {
            List<String> slot = recipe.ingredientSlots().get(s);
            for (int copy = 0; !slot.isEmpty() && copy < recipe.ingredientSlotCount(s); copy++) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /**
     * Checks the well-formedness of a solved plan's layouts: aligned 1:1 with the steps, each
     * step's runs summing to its crafts, no two adjacent runs listing equal items, and every layout
     * item one of that slot's alternatives. Returns null when all hold, otherwise what broke.
     */
    private String layoutInvariants(TestRecipe target, List<PlannedStep<String>> steps,
                                    List<List<SlotLayout<String>>> layouts) {
        if (layouts.size() != steps.size()) {
            return layouts.size() + " layouts for " + steps.size() + " steps";
        }
        for (int i = 0; i < steps.size(); i++) {
            TestRecipe recipe = byId(target, steps.get(i).recipeId());
            List<List<String>> slots = expandedSlots(recipe);
            long crafts = 0;
            List<SlotLayout<String>> runs = layouts.get(i);
            for (int r = 0; r < runs.size(); r++) {
                SlotLayout<String> run = runs.get(r);
                crafts += run.crafts();
                if (run.items().size() != slots.size()) {
                    return "step " + i + " run " + r + " lists " + run.items().size()
                            + " items for " + slots.size() + " non-empty slots";
                }
                for (int s = 0; s < slots.size(); s++) {
                    if (!slots.get(s).contains(run.items().get(s))) {
                        return "step " + i + " run " + r + " puts " + run.items().get(s)
                                + " in a slot accepting " + slots.get(s);
                    }
                }
                if (r > 0 && runs.get(r - 1).items().equals(run.items())) {
                    return "step " + i + " runs " + (r - 1) + " and " + r + " list equal items";
                }
            }
            if (crafts != steps.get(i).crafts()) {
                return "step " + i + " runs sum to " + crafts + " crafts, step holds "
                        + steps.get(i).crafts();
            }
        }
        return null;
    }

    /**
     * Replays {@code steps} one craft at a time under the plan's OWN layouts — the 1.1.0 contract:
     * every layout item must be in stock for each craft, is consumed, then the result and the
     * remainders of what was consumed are credited. Deliberately independent of the solver's own
     * (closed-form) acceptance check. Returns null when every craft was paid for, otherwise where
     * it came up short.
     */
    private String replayLayoutsCraftByCraft(TestRecipe target, List<PlannedStep<String>> steps,
                                             List<List<SlotLayout<String>>> layouts,
                                             Map<String, Integer> inventory) {
        Map<String, Integer> stock = new HashMap<>(inventory);
        for (int i = 0; i < steps.size(); i++) {
            TestRecipe recipe = byId(target, steps.get(i).recipeId());
            for (SlotLayout<String> run : layouts.get(i)) {
                for (int craft = 0; craft < run.crafts(); craft++) {
                    Map<String, Integer> need = new HashMap<>();
                    for (String item : run.items()) {
                        need.merge(item, 1, Integer::sum);
                    }
                    if (recipe.station() != null) {
                        // The station stands while the craft runs: one unit beyond what it consumes.
                        need.merge(recipe.station(), 1, Integer::sum);
                    }
                    for (Map.Entry<String, Integer> entry : need.entrySet()) {
                        if (stock.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                            return "step " + i + " (" + steps.get(i).recipeId() + ") craft " + craft
                                    + ": needs " + entry.getValue() + " " + entry.getKey() + ", holds "
                                    + stock.getOrDefault(entry.getKey(), 0) + "; layouts " + layouts;
                        }
                    }
                    for (String item : run.items()) {
                        stock.merge(item, -1, Integer::sum);
                    }
                    stock.merge(recipe.result(), recipe.resultCount(), Integer::sum);
                    for (String item : run.items()) {
                        String leftBehind = remainders.get(item);
                        if (leftBehind != null) {
                            stock.merge(leftBehind, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Replays {@code steps} one craft at a time, each craft's slots filled by
     * {@link SlotAssignment#assign} over the current stock, crediting the result and remainders after
     * each craft: what an executor that follows the shared fallback rule does. Deliberately
     * independent of {@link PlanSimulator}'s batching. Returns null when every slot of every craft
     * was filled, otherwise where it came up short.
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

    /**
     * solve() succeeds, its layouts are well-formed, and the plan replays craft by craft under its
     * own layouts — what an executor that follows them (part 2) will run.
     */
    private void assertExecutable(TestRecipe target, Map<String, Integer> inventory) {
        Result<String, String> result = solver().solve(target, inventory);
        assertInstanceOf(Result.Success.class, result, "expected solvable, got " + result);
        Result.Success<String, String> success = (Result.Success<String, String>) result;
        List<PlannedStep<String>> steps = success.steps();
        assertEquals(null, layoutInvariants(target, steps, success.layouts()),
                "plan " + steps + " has malformed layouts: " + success.layouts());
        assertEquals(null, replayLayoutsCraftByCraft(target, steps, success.layouts(), inventory),
                "plan " + steps + " does not replay under its own layouts " + success.layouts());
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
        // The executors' per-cell form: blank grid cells (null) stay null and the picks stay aligned.
        assertEquals(Arrays.asList(null, "birch", null, "oak"), SlotAssignment.assignCells(
                Arrays.asList(null, Arrays.asList("oak", "birch"), null, Arrays.asList("oak")),
                inv("oak", 1, "birch", 1)));
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
            Result.Success<String, String> success = (Result.Success<String, String>) result;
            assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held),
                    "plan " + success.steps() + " shorts under its own layouts " + success.layouts());
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
        // The property review_final fuzzed: whatever solve() returns replays craft by craft under its
        // OWN layouts, an executor following them never comes up short, and the layouts are exactly
        // the per-slot alternatives. Also keeps PlanSimulator's batched replay agreeing with the
        // craft-by-craft SlotAssignment one on perturbed (often broken) plans — that pair tests
        // PlanSimulator itself.
        java.util.Random random = new java.util.Random(0x5C100018L);
        int trials = 3000;
        int solved = 0;
        for (int trial = 0; trial < trials; trial++) {
            RandomGraph graph = randomGraph(random);
            TestRecipe target = graph.target;
            Map<String, Integer> held = graph.held;
            Result<String, String> result = solver().solve(target, held);
            if (!(result instanceof Result.Success)) {
                continue;
            }
            solved++;
            Result.Success<String, String> success = (Result.Success<String, String>) result;
            List<PlannedStep<String>> steps = success.steps();
            List<List<SlotLayout<String>>> layouts = success.layouts();
            String context = "trial " + trial + " (" + solved + " solved so far): target "
                    + target.ingredientSlots() + ", recipes " + describe(recipes) + ", held " + held
                    + ", remainders " + remainders + ", plan " + steps;
            assertEquals(null, layoutInvariants(target, steps, layouts),
                    context + " has malformed layouts");
            assertEquals(null, replayLayoutsCraftByCraft(target, steps, layouts, held),
                    context + " does not replay under its own layouts " + layouts);
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

    /** One random recipe graph as {@link #randomGraph} builds it: the target and what is held. */
    private static final class RandomGraph {
        final TestRecipe target;
        final Map<String, Integer> held;

        RandomGraph(TestRecipe target, Map<String, Integer> held) {
            this.target = target;
            this.held = held;
        }
    }

    /**
     * The fixed-seed random-graph generator the executability property runs on: refills
     * {@link #recipes} and {@link #remainders} with a small graph (three mids over four raws, one or
     * two variants each, some cycles) and returns its target and a random inventory.
     */
    private RandomGraph randomGraph(java.util.Random random) {
        String[] raws = {"r0", "r1", "r2", "r3"};
        String[] mids = {"m0", "m1", "m2"};
        int[] yields = {1, 2, 3, 4, 9};
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
        return new RandomGraph(target, held);
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
    void subCraftLeavesTheOnlyCopyALaterSlotNeeds() {
        // #23: x takes oak or birch; the target needs the one oak AND an x. A per-craft rule makes x
        // from oak (its first preference) and the target comes up short; the plan must make x from
        // birch and say so, so the executor does not take the oak.
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "oak|birch"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "oak", "x");
        Result<String, String> result = solver().solve(target, inv("oak", 1, "birch", 1));
        Result.Success<String, String> success =
                assertInstanceOf(Result.Success.class, result, "expected solvable, got " + result);
        assertEquals(Arrays.asList(new PlannedStep<>("x_recipe", 1), new PlannedStep<>("t", 1)),
                success.steps());
        // The layouts carry the choice: x_recipe's craft takes the birch, the target's slots the oak
        // and the crafted x.
        assertEquals(Arrays.asList(
                        Arrays.asList(new SlotLayout<>(1, Arrays.asList("birch"))),
                        Arrays.asList(new SlotLayout<>(1, Arrays.asList("oak", "x")))),
                success.layouts());
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

    /**
     * A compression chain whose quantities, not its shape, are large (issue #43): c0 from three
     * raws, each c(k) from six c(k-1) and three of raw b. Every item below the top also has a
     * decompress recipe, registered before its real one so it is the first producer tried: the
     * dead-end candidate (ingot from block) a real recipe set puts in the way.
     * Returns the target, c(levels); making one takes 6^levels leaf crafts.
     */
    private TestRecipe quantityChain(int levels) {
        return quantityChain(levels, true);
    }

    /** {@code unpackFirst} false registers each decompress recipe after the item's real producer. */
    private TestRecipe quantityChain(int levels, boolean unpackFirst) {
        for (int k = 1; unpackFirst && k <= levels; k++) {
            recipes.add(TestRecipe.of("unpack" + k, "c" + (k - 1), 6, "c" + k));
        }
        recipes.add(TestRecipe.of("leaf", "c0", 1, "a", "a", "a", "a", "b", "b", "b", "b", "d"));
        TestRecipe top = null;
        for (int k = 1; k <= levels; k++) {
            String lower = "c" + (k - 1);
            top = TestRecipe.of("pack" + k, "c" + k, 1, lower, lower, lower, lower, lower, lower, "b", "b", "b");
            recipes.add(top);
            if (!unpackFirst) {
                recipes.add(TestRecipe.of("unpack" + k, "c" + (k - 1), 6, "c" + k));
            }
        }
        return top;
    }

    @Test
    void largeStockThatRunsShortIsAShortfallNotABudgetFailure() {
        // 6^10 = 60,466,176 leaf crafts need 241,864,704 of raw a; ten million are held. The answer is
        // "missing a", however many visits the leftovers of a large stock would cost to chase (with
        // the real producer first, every budgeted pass runs out chasing them).
        TestRecipe target = quantityChain(10, false);
        Result<String, String> result = solver().solve(target, inv("a", 10_000_000, "b", 10_000_000, "d", 10_000_000));
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class, result);
        assertFalse(failure.budgetExceeded(), "a real shortfall, not a tree too complex to plan");
        assertFalse(failure.missing().isEmpty(), "a shortfall names something to gather");
    }

    @Test
    void quantityPathDoesNotCallAnAssignmentConflictAShortfall() {
        // The top recipe adds oak|birch, oak, oak|birch with two of each held: makeable (oak to the
        // middle slot), but a batched search hands oak to the flexible slots first. Whatever the
        // quantity path answers for such a tree, it must not be "you are missing oak".
        quantityChain(17);
        TestRecipe target = TestRecipe.of("top", "top", 1, "c17", "oak|birch", "oak", "oak|birch");
        int unbounded = Integer.MAX_VALUE;
        Result<String, String> result = solver().solve(target,
                inv("a", unbounded, "b", unbounded, "d", unbounded, "oak", 2, "birch", 2));
        if (result instanceof Result.Failure) {
            assertTrue(((Result.Failure<String, String>) result).budgetExceeded(),
                    "a makeable tree was reported short of " + ((Result.Failure<String, String>) result).missing());
        }
    }

    @Test
    void quantityPathStillAsksAGeneratingCycleAgain() {
        // One i1 makes three i0 and one i0 makes one i1: two i1 become nine i0 only by asking the
        // first recipe again after its batch shrank to the i1 on hand.
        quantityChain(17);
        recipes.add(TestRecipe.of("split", "i0", 3, "i1"));
        recipes.add(TestRecipe.of("join", "i1", 1, "i0"));
        TestRecipe target = TestRecipe.of("top", "top", 1, "c17", "i0", "i0", "i0", "i0", "i0", "i0", "i0", "i0");
        int unbounded = Integer.MAX_VALUE;
        assertInstanceOf(Result.Success.class, solver().solve(target,
                inv("a", unbounded, "b", unbounded, "d", unbounded, "i1", 2)));
    }

    @Test
    void quantityAloneDoesNotExhaustTheAttemptBudget() {
        // 6^17 leaf crafts is far more int-sized batches of the leaf recipe alone than there are
        // attempts (4,096), in a chain of 18 items. Unbounded raws, so it must plan, crafting exactly that.
        TestRecipe target = quantityChain(17);
        int unbounded = Integer.MAX_VALUE;
        Result<String, String> result = solver().solve(target, inv("a", unbounded, "b", unbounded, "d", unbounded));
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class, result);
        long leafCrafts = 0;
        for (PlannedStep<String> step : success.steps()) {
            if (step.recipeId().equals("leaf")) {
                leafCrafts += step.crafts();
            }
        }
        assertEquals(16_926_659_444_736L, leafCrafts);
    }

    @Test
    void quantityPlanLengthDoesNotGrowWithQuantity() {
        // The same makeable tree with room for only 64 steps: still planned, in about one step per
        // recipe (a step records its recipe's whole demand as a long count), so the plan's length
        // tracks the tree's shape, not its quantities.
        TestRecipe target = quantityChain(17);
        int unbounded = Integer.MAX_VALUE;
        RecipeGraphSolver<String, String> small = new RecipeGraphSolver<>(
                item -> recipes.stream().filter(r -> r.result().equals(item)).collect(Collectors.toList()),
                remainders::get, 32, 4096, 64);
        Result<String, String> result = small.solve(target, inv("a", unbounded, "b", unbounded, "d", unbounded));
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class, result);
        Set<String> distinct = new HashSet<>();
        for (PlannedStep<String> step : success.steps()) {
            distinct.add(step.recipeId());
        }
        assertTrue(success.steps().size() <= 2 * distinct.size(),
                "about one step per recipe expected, got " + success.steps());
        long leafCrafts = 0;
        for (PlannedStep<String> step : success.steps()) {
            if (step.recipeId().equals("leaf")) {
                leafCrafts += step.crafts();
            }
        }
        assertEquals(16_926_659_444_736L, leafCrafts);
    }

    @Test
    void intSizedPlanLongerThanTheStepCapIsReportedTooLarge() {
        // The depth-first int-sized plan of the same makeable tree with room for 64 steps: refused, and
        // said to be too large rather than short of anything. budgetExceeded stays set for callers that
        // only read that.
        TestRecipe target = quantityChain(17);
        int unbounded = Integer.MAX_VALUE;
        RecipeGraphSolver<String, String> small = new RecipeGraphSolver<>(
                item -> recipes.stream().filter(r -> r.result().equals(item)).collect(Collectors.toList()),
                remainders::get, 32, 4096, 64);
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class,
                small.solveIntSized(target, inv("a", unbounded, "b", unbounded, "d", unbounded)));
        assertTrue(failure.tooLarge(), "makeable, but longer than the step cap");
        assertTrue(failure.budgetExceeded());
    }

    // --- issue #13: stations, per-slot quantities, a cost model, a null remainder function ----------

    private List<TestRecipe> producersOf(String item) {
        return recipes.stream().filter(r -> r.result().equals(item)).collect(Collectors.toList());
    }

    private RecipeGraphSolver<String, String> solver(CostModel<String, String> costs) {
        return solver(costs, 32);
    }

    private RecipeGraphSolver<String, String> solver(CostModel<String, String> costs, int maxDepth) {
        return solver(costs, maxDepth, 4096);
    }

    private RecipeGraphSolver<String, String> solver(CostModel<String, String> costs, int maxDepth, int maxAttempts) {
        return new RecipeGraphSolver<>(this::producersOf, remainders::get, maxDepth, maxAttempts,
                RecipeGraphSolver.DEFAULT_MAX_STEPS, costs);
    }

    /** The items {@link #randomGraph} builds recipes over. */
    private static final String[] RANDOM_ITEMS = {"r0", "r1", "r2", "r3", "m0", "m1", "m2"};

    /**
     * A random cost model over {@link #randomGraph}'s current recipes: some items ungatherable, some mids
     * cheaper to gather than to craft, some recipes whose own cost is unobtainable.
     */
    private MapCosts randomCosts(java.util.Random random) {
        MapCosts costs = new MapCosts();
        for (String item : RANDOM_ITEMS) {
            if (random.nextInt(4) != 0) {
                costs.gather(item, 1 + random.nextInt(9));
            }
        }
        for (TestRecipe recipe : recipes) {
            costs.recipe(recipe.id(), random.nextInt(6) == 0 ? CostModel.UNOBTAINABLE : random.nextInt(4));
        }
        return costs;
    }

    /** Costs by item and recipe id: an item without a gather cost is unobtainable, a recipe costs 1 unless set. */
    private static final class MapCosts implements CostModel<String, String> {
        private final Map<String, Long> gather = new HashMap<>();
        private final Map<String, Long> recipe = new HashMap<>();

        MapCosts gather(String item, long cost) {
            gather.put(item, cost);
            return this;
        }

        MapCosts recipe(String id, long cost) {
            recipe.put(id, cost);
            return this;
        }

        @Override
        public long gatherCost(String item) {
            Long cost = gather.get(item);
            return cost == null ? UNOBTAINABLE : cost;
        }

        @Override
        public long recipeCost(RecipeInfo<String, String> r) {
            Long cost = recipe.get(r.id());
            return cost == null ? 1 : cost;
        }
    }

    private static void assertSameResult(Result<String, String> expected, Result<String, String> actual,
                                         String context) {
        if (expected instanceof Result.Success) {
            Result.Success<String, String> want = (Result.Success<String, String>) expected;
            Result.Success<String, String> got = assertInstanceOf(Result.Success.class, actual, context);
            assertEquals(want.steps(), got.steps(), context);
            assertEquals(want.layouts(), got.layouts(), context);
        } else {
            Result.Failure<String, String> want = (Result.Failure<String, String>) expected;
            Result.Failure<String, String> got = assertInstanceOf(Result.Failure.class, actual, context);
            assertEquals(want.missing(), got.missing(), context);
            assertEquals(want.budgetExceeded(), got.budgetExceeded(), context);
            assertEquals(want.tooLarge(), got.tooLarge(), context);
        }
    }

    private static void assertSameShortfall(RecipeGraphSolver.ShortfallResult<String> expected,
                                            RecipeGraphSolver.ShortfallResult<String> actual, String context) {
        assertEquals(expected.demandsLong(), actual.demandsLong(), context);
        assertEquals(expected.alternatives(), actual.alternatives(), context);
        assertEquals(expected.approximate(), actual.approximate(), context);
        assertEquals(expected.saturated(), actual.saturated(), context);
    }

    @SuppressWarnings("unchecked")
    private static List<PlannedStep<String>> stepsOf(Result<String, String> result) {
        return ((Result.Success<String, String>) assertInstanceOf(Result.Success.class, result,
                "expected solvable, got " + result)).steps();
    }

    // --- the null remainder function the constructor documents -------------------------------------

    @Test
    void solveShortfallAndReplayWorkWhenTheRemainderFunctionIsNull() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        RecipeGraphSolver<String, String> noRemainders = new RecipeGraphSolver<>(this::producersOf, null, 32, 4096);
        List<PlannedStep<String>> steps = stepsOf(noRemainders.solve(chest, inv("log", 2)));
        assertEquals(Arrays.asList(new PlannedStep<>("planks", 2), new PlannedStep<>("chest", 1)), steps);
        assertEquals(inv("log", 2), noRemainders.shortfall(chest, inv()).demands());
        assertTrue(PlanSimulator.replay(steps, id -> byId(chest, id), null, inv("log", 2)).ok());
    }

    // --- stations ----------------------------------------------------------------------------------

    /** A log makes four planks and four planks make a table. */
    private void tableRecipes() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        recipes.add(TestRecipe.of("table_recipe", "table", 1, "planks", "planks", "planks", "planks"));
    }

    @Test
    void aStationThatIsNotInStockIsCraftedBeforeTheStepThatNeedsIt() {
        tableRecipes();
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gold").withStation("table");
        assertEquals(Arrays.asList(
                        new PlannedStep<>("planks", 1),
                        new PlannedStep<>("table_recipe", 1),
                        new PlannedStep<>("ring", 1)),
                solveOk(ring, inv("gold", 1, "log", 1)));
    }

    @Test
    void aStationIsCraftedOnceEvenWhenTheStepBatchesManyCrafts() {
        recipes.add(TestRecipe.of("furnace_recipe", "furnace", 1,
                "cobble", "cobble", "cobble", "cobble", "cobble", "cobble", "cobble", "cobble"));
        recipes.add(TestRecipe.of("bar_recipe", "bar", 1, "ore").withStation("furnace"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "bar", "bar", "bar", "bar", "bar", "bar", "bar", "bar");
        assertEquals(Arrays.asList(
                        new PlannedStep<>("furnace_recipe", 1),
                        new PlannedStep<>("bar_recipe", 8),
                        new PlannedStep<>("t", 1)),
                solveOk(target, inv("ore", 8, "cobble", 8)));
    }

    @Test
    void aStationAlreadyInStockIsNotCrafted() {
        recipes.add(TestRecipe.of("furnace_recipe", "furnace", 1,
                "cobble", "cobble", "cobble", "cobble", "cobble", "cobble", "cobble", "cobble"));
        recipes.add(TestRecipe.of("bar_recipe", "bar", 1, "ore").withStation("furnace"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "bar", "bar", "bar", "bar", "bar", "bar", "bar", "bar");
        assertEquals(Arrays.asList(new PlannedStep<>("bar_recipe", 8), new PlannedStep<>("t", 1)),
                solveOk(target, inv("ore", 8, "furnace", 1, "cobble", 8)));
    }

    @Test
    void aStationIsNotConsumedSoALaterStepWithTheSameStationNeedsNoSecondOne() {
        tableRecipes();
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "rawx").withStation("table"));
        recipes.add(TestRecipe.of("y_recipe", "y", 1, "rawy").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x", "y");
        // Planks for ONE table only: a second craft of the table would not be possible.
        Map<String, Integer> held = inv("rawx", 1, "rawy", 1, "planks", 4);
        List<PlannedStep<String>> steps = solveOk(target, held);
        assertEquals(Arrays.asList(
                        new PlannedStep<>("table_recipe", 1),
                        new PlannedStep<>("x_recipe", 1),
                        new PlannedStep<>("y_recipe", 1),
                        new PlannedStep<>("t", 1)),
                steps);
        assertTrue(simulate(target, steps, held).ok(), "replay: " + simulate(target, steps, held));
    }

    @Test
    void aStationInStockServesEveryStepThatNeedsItWithoutBeingUsedUp() {
        tableRecipes();
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "rawx").withStation("table"));
        recipes.add(TestRecipe.of("y_recipe", "y", 1, "rawy").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x", "y");
        // The one held table is no use to a second step if the first one takes it; no planks for another.
        assertEquals(Arrays.asList(new PlannedStep<>("x_recipe", 1), new PlannedStep<>("y_recipe", 1),
                        new PlannedStep<>("t", 1)),
                solveOk(target, inv("rawx", 1, "rawy", 1, "table", 1)));
    }

    @Test
    void aStationIsCraftedFirstForAStepWhoseSlotsCompeteForTheSameStock() {
        tableRecipes();
        // The oak-or-birch slot would take the only oak first, leaving the oak-only slot short: the
        // slot-by-slot passes fail and the exact pass (most constrained slot first) plans the step.
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "a|b", "a").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        Map<String, Integer> held = inv("a", 1, "b", 1, "planks", 4);
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class, solver().solve(target, held));
        assertEquals(Arrays.asList(new PlannedStep<>("table_recipe", 1), new PlannedStep<>("x_recipe", 1),
                new PlannedStep<>("t", 1)), success.steps());
        assertEquals(Arrays.asList(new SlotLayout<>(1, Arrays.asList("b", "a"))), success.layouts().get(1));
        assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held));
        // And without any way to get a table the same step fails, naming it.
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class,
                solver().solve(target, inv("a", 1, "b", 1)));
        assertTrue(failure.missing().contains("table"), "got " + failure.missing());
    }

    @Test
    void aStationTheExactPassedRecipeAlsoConsumesIsMadeAgain() {
        tableRecipes();
        // As above (the oak-or-birch slot forces the exact pass), and the recipe also eats a table: the
        // one held table cannot be both an ingredient and the place the step runs, so a second is made.
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "a|b", "a", "table").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        Map<String, Integer> held = inv("a", 1, "b", 1, "table", 1, "planks", 4);
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class, solver().solve(target, held));
        assertEquals(Arrays.asList(new PlannedStep<>("table_recipe", 1), new PlannedStep<>("x_recipe", 1),
                new PlannedStep<>("t", 1)), success.steps());
        assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held));
        assertInstanceOf(Result.Failure.class, solver().solve(target, inv("a", 1, "b", 1, "table", 1, "planks", 3)));
    }

    @Test
    void aSlotThatAlsoAcceptsTheStationTakesAnotherAlternativeAndLeavesTheStationStanding() {
        // x's one slot takes a table or a log, and x runs at a table. Filling the slot with the only table
        // would leave nowhere to craft; the plan must take the log and keep the table standing.
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "table|log").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        Map<String, Integer> held = inv("table", 1, "log", 1);
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class,
                solver().solve(target, held), "solve " + solver().solve(target, held));
        assertEquals(Arrays.asList(new PlannedStep<>("x_recipe", 1), new PlannedStep<>("t", 1)), success.steps());
        assertEquals(Arrays.asList(new SlotLayout<>(1, Arrays.asList("log"))), success.layouts().get(0));
        assertTrue(simulate(target, success.steps(), held).ok(), "replay " + simulate(target, success.steps(), held));
        assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held));
        // The same holds where the recipe is the target itself, and the shortfall agrees there is nothing to gather.
        TestRecipe direct = TestRecipe.of("y", "y", 1, "table|log").withStation("table");
        assertEquals(Arrays.asList(new PlannedStep<>("y", 1)), solveOk(direct, held));
        assertEquals(Collections.emptyMap(), solver().shortfall(target, held).demands());
        // And where the exact pass could not repair it: x's slot also accepts the oak the target's own slot
        // needs, and the exact pass's per-craft choice takes that oak. Only reserving the table in the first
        // passes, which then fill x from the birch, finds the plan.
        recipes.add(TestRecipe.of("z_recipe", "z", 1, "table|oak|birch").withStation("table"));
        TestRecipe both = TestRecipe.of("u", "u", 1, "oak", "z");
        Map<String, Integer> stock = inv("table", 1, "oak", 1, "birch", 1);
        Result.Success<String, String> mixed = assertInstanceOf(Result.Success.class, solver().solve(both, stock),
                "solve " + solver().solve(both, stock));
        assertEquals(Arrays.asList(new PlannedStep<>("z_recipe", 1), new PlannedStep<>("u", 1)), mixed.steps());
        assertEquals(Arrays.asList(new SlotLayout<>(1, Arrays.asList("birch"))), mixed.layouts().get(0));
        assertEquals(null, replayLayoutsCraftByCraft(both, mixed.steps(), mixed.layouts(), stock));
    }

    @Test
    void aRecipeCannotMakeItsOwnStationWithoutOneAlreadyStanding() {
        recipes.add(TestRecipe.of("table_from_table", "table", 1, "plank").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "table");
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class,
                solver().solve(target, inv("plank", 1)));
        assertTrue(failure.missing().contains("table"), "got " + failure.missing());
    }

    @Test
    void aStationIsHonouredWhenTheQuantityPathPlansTheTree() {
        quantityChain(17);
        for (int i = 0; i < recipes.size(); i++) {
            if (recipes.get(i).id().equals("leaf")) {
                recipes.set(i, recipes.get(i).withStation("bench"));
            }
        }
        recipes.add(TestRecipe.of("bench_recipe", "bench", 1, "d", "d"));
        TestRecipe target = TestRecipe.of("top", "top", 1, "c17");
        int unbounded = Integer.MAX_VALUE;
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class,
                solver().solve(target, inv("a", unbounded, "b", unbounded, "d", unbounded)));
        int benchAt = -1;
        int firstLeaf = -1;
        long leafCrafts = 0;
        for (int i = 0; i < success.steps().size(); i++) {
            PlannedStep<String> step = success.steps().get(i);
            if (step.recipeId().equals("bench_recipe")) {
                assertEquals(-1, benchAt, "the bench is made once");
                benchAt = i;
            } else if (step.recipeId().equals("leaf")) {
                firstLeaf = firstLeaf < 0 ? i : firstLeaf;
                leafCrafts += step.crafts();
            }
        }
        assertTrue(benchAt >= 0 && benchAt < firstLeaf, "bench at " + benchAt + ", first leaf at " + firstLeaf);
        assertEquals(16_926_659_444_736L, leafCrafts);
    }

    @Test
    void aStationCraftedForAStepCanBeConsumedAfterwardsByARecipeThatUsesItAsAnIngredient() {
        tableRecipes();
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "rawx").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x", "table");
        Map<String, Integer> held = inv("rawx", 1, "planks", 4);
        List<PlannedStep<String>> steps = solveOk(target, held);
        // One table serves x_recipe and is then eaten by t: a second table (more planks) is not needed.
        assertEquals(Arrays.asList(
                        new PlannedStep<>("table_recipe", 1),
                        new PlannedStep<>("x_recipe", 1),
                        new PlannedStep<>("t", 1)),
                steps);
        assertTrue(simulate(target, steps, held).ok(), "replay: " + simulate(target, steps, held));
    }

    @Test
    void anUnobtainableStationFailsOverToTheNextProducer() {
        recipes.add(TestRecipe.of("bar_forged", "bar", 1, "ore").withStation("forge"));
        recipes.add(TestRecipe.of("bar_plain", "bar", 1, "ore", "ore"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "bar");
        // The forged recipe is listed first and its ore is in stock, but there is no forge anywhere.
        assertEquals(Arrays.asList(new PlannedStep<>("bar_plain", 1), new PlannedStep<>("t", 1)),
                solveOk(target, inv("ore", 2)));
    }

    @Test
    void anUnobtainableStationIsNamedInTheFailureWhenItIsTheOnlyWay() {
        recipes.add(TestRecipe.of("bar_forged", "bar", 1, "ore").withStation("forge"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "bar");
        Result.Failure<String, String> failure = assertInstanceOf(Result.Failure.class,
                solver().solve(target, inv("ore", 1)));
        assertTrue(failure.missing().contains("forge"), "the station should be named, got " + failure.missing());
        assertFalse(failure.budgetExceeded());
    }

    @Test
    void shortfallCountsAStationsLeavesOnceAndNothingWhenItIsStocked() {
        tableRecipes();
        recipes.add(TestRecipe.of("bar_recipe", "bar", 1, "ore").withStation("table"));
        recipes.add(TestRecipe.of("rod_recipe", "rod", 1, "scrap").withStation("table"));
        TestRecipe eightBars = TestRecipe.of("t", "t", 1, "bar", "bar", "bar", "bar", "bar", "bar", "bar", "bar");
        // Eight crafts, one table: a single log (one table's worth of planks), not one per craft.
        assertEquals(inv("ore", 8, "log", 1), solver().shortfall(eightBars, inv()).demands());
        assertEquals(inv("ore", 8), solver().shortfall(eightBars, inv("table", 1)).demands());
        // Two different recipes at the same table still ask for the one table.
        TestRecipe both = TestRecipe.of("t2", "t2", 1, "bar", "rod");
        assertEquals(inv("ore", 1, "scrap", 1, "log", 1), solver().shortfall(both, inv()).demands());
        // The target's own station is counted too.
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gold").withStation("table");
        assertEquals(inv("gold", 1, "log", 1), solver().shortfall(ring, inv()).demands());
        assertEquals(inv("gold", 1), solver().shortfall(ring, inv("table", 1)).demands());
    }

    @Test
    void replayRejectsAPlanWhoseStationIsAbsentAndDoesNotConsumeTheOneItHas() {
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "rawx").withStation("table"));
        recipes.add(TestRecipe.of("y_recipe", "y", 1, "rawy").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x", "y");
        List<PlannedStep<String>> plan = Arrays.asList(
                new PlannedStep<>("x_recipe", 1), new PlannedStep<>("y_recipe", 1), new PlannedStep<>("t", 1));
        PlanSimulator.Replay<String, String> absent = simulate(target, plan, inv("rawx", 1, "rawy", 1));
        assertFalse(absent.ok(), "no table in stock");
        assertEquals(0, absent.failedStep());
        assertEquals("table", absent.missing());
        // One table serves both steps: replaying must not use it up.
        assertTrue(simulate(target, plan, inv("rawx", 1, "rawy", 1, "table", 1)).ok());
    }

    @Test
    void aStationTheSameRecipeAlsoConsumesNeedsOneUnitMoreThanTheIngredientTakes() {
        tableRecipes();
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "table").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        Map<String, Integer> held = inv("table", 1, "planks", 4);
        List<PlannedStep<String>> steps = solveOk(target, held);
        assertEquals(Arrays.asList(
                        new PlannedStep<>("table_recipe", 1),
                        new PlannedStep<>("x_recipe", 1),
                        new PlannedStep<>("t", 1)),
                steps);
        assertTrue(simulate(target, steps, held).ok());
        // The one held table cannot be both standing and eaten.
        List<PlannedStep<String>> oneCraft = Arrays.asList(new PlannedStep<>("x_recipe", 1));
        assertFalse(simulate(target, oneCraft, inv("table", 1)).ok(), "the station is held while the step runs");
        assertInstanceOf(Result.Failure.class, solver().solve(target, inv("table", 1, "planks", 3)));
    }

    /**
     * solve() plans {@code expected}, the plan replays, and shortfall() agrees there is nothing to gather
     * and does not flag that approximate: with and without a cost model.
     */
    private void assertPlansAndNeedsNothing(TestRecipe target, Map<String, Integer> held,
                                            List<PlannedStep<String>> expected) {
        for (RecipeGraphSolver<String, String> solver : Arrays.asList(solver(), solver(new MapCosts()
                .gather("ore", 1).gather("log", 1).gather("table", 50)))) {
            Result<String, String> result = solver.solve(target, held);
            Result.Success<String, String> success = assertInstanceOf(Result.Success.class, result,
                    "solve " + result + (result instanceof Result.Failure
                            ? " missing " + ((Result.Failure<String, String>) result).missing() : ""));
            assertEquals(expected, success.steps());
            assertTrue(solver.isSolvable(target, held));
            assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held));
            assertTrue(simulate(target, success.steps(), held).ok(), "replay " + simulate(target, success.steps(), held));
            RecipeGraphSolver.ShortfallResult<String> shortfall = solver.shortfall(target, held);
            assertEquals(Collections.emptyMap(), shortfall.demands());
            assertFalse(shortfall.approximate(), "an empty shortfall for a solvable target is exact");
        }
    }

    @Test
    void aSubCraftAtTheSameStationSharesTheOneStandingUnit() {
        // A hopper is crafted at a table from a chest crafted at the same table: the sub-craft needs no second
        // table, and the target's step does not lose its table to the sub-craft.
        recipes.add(TestRecipe.of("ingot_recipe", "ingot", 1, "ore").withStation("table"));
        TestRecipe target = TestRecipe.of("TARGET", "TARGET", 1, "ingot").withStation("table");
        assertPlansAndNeedsNothing(target, inv("table", 1, "ore", 1),
                Arrays.asList(new PlannedStep<>("ingot_recipe", 1), new PlannedStep<>("TARGET", 1)));
    }

    @Test
    void aStationThePlanCraftsServesNestedStepsAtThatStationAndIsMadeOnce() {
        // Two levels at the table under a target at the table, and the table itself made in the plan from a log:
        // one table, crafted first, stands for all three steps.
        tableRecipes();
        recipes.add(TestRecipe.of("ingot2_recipe", "ingot2", 1, "ore").withStation("table"));
        recipes.add(TestRecipe.of("ingot_recipe", "ingot", 1, "ingot2").withStation("table"));
        TestRecipe target = TestRecipe.of("TARGET", "TARGET", 1, "ingot").withStation("table");
        assertPlansAndNeedsNothing(target, inv("log", 1, "ore", 1), Arrays.asList(
                new PlannedStep<>("planks", 1),
                new PlannedStep<>("table_recipe", 1),
                new PlannedStep<>("ingot2_recipe", 1),
                new PlannedStep<>("ingot_recipe", 1),
                new PlannedStep<>("TARGET", 1)));
        // The same with the table held: nothing is crafted for it.
        assertPlansAndNeedsNothing(target, inv("table", 1, "ore", 1), Arrays.asList(
                new PlannedStep<>("ingot2_recipe", 1),
                new PlannedStep<>("ingot_recipe", 1),
                new PlannedStep<>("TARGET", 1)));
    }

    @Test
    void theExactPassLetsASubCraftShareItsParentsStation() {
        // x's oak-or-birch slot forces the exact pass (see aStationIsCraftedFirstForAStepWhoseSlotsCompeteForTheSameStock);
        // its third slot is sub-crafted at the table x runs at, inside that pass's repair loop.
        recipes.add(TestRecipe.of("c_recipe", "c", 1, "ore").withStation("table"));
        recipes.add(TestRecipe.of("x_recipe", "x", 1, "a|b", "a", "c").withStation("table"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x");
        assertPlansAndNeedsNothing(target, inv("a", 1, "b", 1, "table", 1, "ore", 1), Arrays.asList(
                new PlannedStep<>("c_recipe", 1), new PlannedStep<>("x_recipe", 1), new PlannedStep<>("t", 1)));
    }

    @Test
    void presentStationsThatNoRecipeConsumesNeverMakeASolvableTargetUnsolvable() {
        // On random graphs, give random recipes (the target included) a station drawn from items no recipe makes
        // or eats. Whatever the graph solves without stations, it solves with them given one unit of each, and
        // that plan replays with every station standing: with and without a cost model (stations cost nothing to
        // gather there, so the cost order is the same as without them).
        java.util.Random random = new java.util.Random(0x5C13C0DEL);
        String[] stations = {"s0", "s1"};
        int trials = 1500;
        int compared = 0;
        int withNested = 0;
        for (int trial = 0; trial < trials; trial++) {
            RandomGraph graph = randomGraph(random);
            MapCosts costs = randomCosts(random);
            List<TestRecipe> plain = new ArrayList<>(recipes);
            List<TestRecipe> stationed = new ArrayList<>();
            for (TestRecipe recipe : plain) {
                stationed.add(random.nextInt(3) > 0 ? recipe.withStation(stations[random.nextInt(2)]) : recipe);
            }
            TestRecipe plainTarget = graph.target;
            TestRecipe stationedTarget = random.nextBoolean()
                    ? plainTarget.withStation(stations[random.nextInt(2)]) : plainTarget;
            Map<String, Integer> withStations = new HashMap<>(graph.held);
            for (String station : stations) {
                withStations.put(station, 1);
                costs.gather(station, 0);
            }
            for (int withCosts = 0; withCosts < 2; withCosts++) {
                String context = "trial " + trial + (withCosts == 0 ? " (no costs)" : " (costs)") + ": target "
                        + stationedTarget.ingredientSlots() + " at " + stationedTarget.station() + ", recipes "
                        + describeWithStations(stationed) + ", held " + graph.held + ", remainders " + remainders
                        + ", gather " + costs.gather + ", recipe costs " + costs.recipe;
                recipes.clear();
                recipes.addAll(plain);
                Result<String, String> without = solver(withCosts == 0 ? null : costs, 32, 1 << 16)
                        .solve(plainTarget, graph.held);
                if (!(without instanceof Result.Success)) {
                    continue;
                }
                recipes.clear();
                recipes.addAll(stationed);
                Result<String, String> with = solver(withCosts == 0 ? null : costs, 32, 1 << 16)
                        .solve(stationedTarget, withStations);
                compared++;
                Result.Success<String, String> success = assertInstanceOf(Result.Success.class, with,
                        context + ": solvable without stations as " + stepsOf(without) + ", with them " + with
                                + (with instanceof Result.Failure ? " missing "
                                + ((Result.Failure<String, String>) with).missing() : ""));
                assertEquals(null, replayLayoutsCraftByCraft(stationedTarget, success.steps(), success.layouts(),
                        withStations), context + ": plan " + success.steps() + " does not replay");
                if (stationedTarget.station() != null && success.steps().size() > 1) {
                    withNested++;
                }
            }
        }
        assertTrue(compared >= trials / 10, "only " + compared + " graphs solved without stations");
        assertTrue(withNested >= trials / 20, "only " + withNested + " plans nested steps under a stationed target");
    }

    private static String describeWithStations(List<TestRecipe> list) {
        StringBuilder out = new StringBuilder();
        for (TestRecipe recipe : list) {
            out.append(recipe.id()).append(recipe.ingredientSlots()).append("->").append(recipe.resultCount())
                    .append(' ').append(recipe.result()).append(" @").append(recipe.station()).append("; ");
        }
        return out.toString();
    }

    // --- per-slot quantities ------------------------------------------------------------------------

    /** The quantified recipe must solve, count shortfall and replay exactly like its slots written out. */
    private void assertSameAsCopies(TestRecipe quantified, TestRecipe copies, Map<String, Integer> stock) {
        String context = "slots " + quantified.ingredientSlots() + " with quantities, stock " + stock;
        Result<String, String> expected = solver().solve(copies, stock);
        assertSameResult(expected, solver().solve(quantified, stock), context);
        assertSameShortfall(solver().shortfall(copies, stock), solver().shortfall(quantified, stock), context);
        if (expected instanceof Result.Success) {
            List<PlannedStep<String>> steps = stepsOf(expected);
            assertEquals(simulate(copies, steps, stock).toString(), simulate(quantified, steps, stock).toString(),
                    context + ", replay of " + steps);
            // A longer batch than the stock covers: both must come up short in the same place.
            List<PlannedStep<String>> longer = new ArrayList<>(steps);
            int last = longer.size() - 1;
            longer.set(last, longer.get(last).withCrafts(longer.get(last).crafts() + 1));
            assertEquals(simulate(copies, longer, stock).toString(), simulate(quantified, longer, stock).toString(),
                    context + ", replay of " + longer);
        }
    }

    @Test
    void aSlotQuantityPlansCountsAndReplaysLikeThatManyIdenticalSlots() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe three = TestRecipe.of("t", "t", 1, "planks").withSlotCounts(3);
        TestRecipe threeSlots = TestRecipe.of("t", "t", 1, "planks", "planks", "planks");
        for (Map<String, Integer> stock : Arrays.asList(inv(), inv("planks", 3), inv("planks", 1, "log", 1),
                inv("log", 1), inv("planks", 2), inv("planks", 5, "log", 9))) {
            assertSameAsCopies(three, threeSlots, stock);
        }
        // The premise: a quantity of 3 is not a quantity of 1, so the cases above are not vacuous.
        assertEquals(Arrays.asList(new PlannedStep<>("planks", 1), new PlannedStep<>("t", 1)),
                solveOk(three, inv("planks", 1, "log", 1)));
        assertInstanceOf(Result.Failure.class, solver().solve(three, inv("planks", 2)));
        assertEquals(inv("log", 1), solver().shortfall(three, inv()).demands());
        assertEquals(Collections.emptyMap(), solver().shortfall(three, inv("planks", 3)).demands());
        // The layout lists the slot three times.
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class,
                solver().solve(three, inv("planks", 3)));
        assertEquals(Arrays.asList(Arrays.asList(new SlotLayout<>(1, Arrays.asList("planks", "planks", "planks")))),
                success.layouts());
    }

    @Test
    void eachCopyOfAQuantifiedSlotMayBeFilledFromADifferentAlternative() {
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 2, "oak_log"));
        recipes.add(TestRecipe.of("birch_planks", "birch_planks", 2, "birch_log"));
        TestRecipe quantified = TestRecipe.of("t", "t", 1, "oak_planks|birch_planks", "stick").withSlotCounts(3, 1);
        TestRecipe copies = TestRecipe.of("t", "t", 1,
                "oak_planks|birch_planks", "oak_planks|birch_planks", "oak_planks|birch_planks", "stick");
        for (Map<String, Integer> stock : Arrays.asList(
                inv("oak_log", 1, "birch_log", 1, "stick", 1), inv("oak_planks", 1, "birch_log", 1, "stick", 1),
                inv("oak_planks", 1, "birch_planks", 2, "stick", 1), inv("oak_log", 1, "stick", 1), inv(),
                inv("oak_planks", 2, "birch_planks", 1, "stick", 1))) {
            assertSameAsCopies(quantified, copies, stock);
        }
        // Premise: one oak log and one birch log are four planks between them, enough only because
        // the three copies may mix the two kinds.
        assertInstanceOf(Result.Success.class, solver().solve(quantified, inv("oak_log", 1, "birch_log", 1, "stick", 1)));
        Result.Success<String, String> mixed = assertInstanceOf(Result.Success.class,
                solver().solve(quantified, inv("oak_planks", 1, "birch_planks", 2, "stick", 1)));
        assertEquals(Arrays.asList(new SlotLayout<>(1, Arrays.asList("oak_planks", "birch_planks", "birch_planks", "stick"))),
                mixed.layouts().get(0));
    }

    @Test
    void aQuantifiedSlotAmongOthersStillCompetesForSharedStockLikeCopies() {
        recipes.add(TestRecipe.of("c_from_a", "c", 1, "a"));
        TestRecipe quantified = TestRecipe.of("t", "t", 1, "a|b", "c", "a").withSlotCounts(2, 1, 1);
        TestRecipe copies = TestRecipe.of("t", "t", 1, "a|b", "a|b", "c", "a");
        for (Map<String, Integer> stock : Arrays.asList(inv("a", 3), inv("a", 2, "b", 1), inv("b", 2, "a", 2),
                inv("a", 1, "b", 2), inv("b", 3), inv("a", 4, "b", 1), inv("c", 1, "a", 1, "b", 2),
                inv("a", 2, "c", 1), inv())) {
            assertSameAsCopies(quantified, copies, stock);
        }
    }

    @Test
    void aSlotQuantityBelowOneIsRejectedWhenTheRecipeIsFirstRead() {
        for (int quantity : new int[] {0, -1}) {
            TestRecipe broken = TestRecipe.of("t", "t", 1, "planks").withSlotCounts(quantity);
            assertThrows(IllegalArgumentException.class, () -> solver().solve(broken, inv("planks", 5)),
                    "solve with quantity " + quantity);
            assertThrows(IllegalArgumentException.class, () -> solver().shortfall(broken, inv()),
                    "shortfall with quantity " + quantity);
            assertThrows(IllegalArgumentException.class,
                    () -> simulate(broken, Arrays.asList(new PlannedStep<>("t", 1)), inv("planks", 5)),
                    "replay with quantity " + quantity);
        }
    }

    // --- cost model --------------------------------------------------------------------------------

    private void plankRecipes() {
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        recipes.add(TestRecipe.of("birch_planks", "birch_planks", 4, "birch_log"));
    }

    private static TestRecipe stick() {
        return TestRecipe.of("stick", "stick", 4, "oak_planks|birch_planks", "oak_planks|birch_planks");
    }

    @Test
    void costModelFillsTheCheapestAlternativeOfASlotWhenNothingIsStocked() {
        plankRecipes();
        MapCosts costs = new MapCosts().gather("oak_log", 5).gather("birch_log", 2);
        // Today's rule takes the first listed alternative; with costs the cheap birch log is named.
        assertEquals(inv("oak_log", 1), solver().shortfall(stick(), inv()).demands());
        assertEquals(inv("birch_log", 1), solver(costs).shortfall(stick(), inv()).demands());
    }

    @Test
    void costModelStillTakesStockBeforeACheaperUnstockedAlternative() {
        plankRecipes();
        MapCosts costs = new MapCosts().gather("oak_log", 5).gather("birch_log", 2);
        Map<String, Integer> held = inv("oak_planks", 2, "birch_log", 1);
        assertEquals(Collections.emptyMap(), solver(costs).shortfall(stick(), held).demands());
        assertEquals(Arrays.asList(new PlannedStep<>("stick", 1)), stepsOf(solver(costs).solve(stick(), held)));
    }

    @Test
    void costModelPlanCraftsTheCheapestAlternativeThatStockCanMake() {
        plankRecipes();
        MapCosts costs = new MapCosts().gather("oak_log", 5).gather("birch_log", 2);
        Map<String, Integer> held = inv("oak_log", 1, "birch_log", 1);
        assertEquals(Arrays.asList(new PlannedStep<>("oak_planks", 1), new PlannedStep<>("stick", 1)),
                solveOk(stick(), held));
        assertEquals(Arrays.asList(new PlannedStep<>("birch_planks", 1), new PlannedStep<>("stick", 1)),
                stepsOf(solver(costs).solve(stick(), held)));
    }

    @Test
    void costModelShortfallCreditsWhatStockCanMakeBeforeGatheringTheCheapestAlternative() {
        plankRecipes();
        MapCosts costs = new MapCosts().gather("oak_log", 5).gather("birch_log", 2).gather("nail", 1);
        String[] eight = new String[9];
        Arrays.fill(eight, "oak_planks|birch_planks");
        eight[8] = "nail";
        TestRecipe chest = TestRecipe.of("chest", "chest", 1, eight);
        // Two oak logs make the eight planks; the cheap birch logs are not needed, only the missing nail.
        assertEquals(inv("nail", 1), solver(costs).shortfall(chest, inv("oak_log", 2)).demands());
        assertEquals(inv("birch_log", 2, "nail", 1), solver(costs).shortfall(chest, inv()).demands());
    }

    @Test
    void costModelAlternativesOfEqualCostKeepTheCallersOrder() {
        plankRecipes();
        MapCosts costs = new MapCosts().gather("oak_log", 3).gather("birch_log", 3);
        TestRecipe oakFirst = TestRecipe.of("a", "a", 1, "oak_planks|birch_planks");
        TestRecipe birchFirst = TestRecipe.of("b", "b", 1, "birch_planks|oak_planks");
        assertEquals(inv("oak_log", 1), solver(costs).shortfall(oakFirst, inv()).demands());
        assertEquals(inv("birch_log", 1), solver(costs).shortfall(birchFirst, inv()).demands());
        Map<String, Integer> both = inv("oak_log", 1, "birch_log", 1);
        assertEquals(Arrays.asList(new PlannedStep<>("oak_planks", 1), new PlannedStep<>("a", 1)),
                stepsOf(solver(costs).solve(oakFirst, both)));
        assertEquals(Arrays.asList(new PlannedStep<>("birch_planks", 1), new PlannedStep<>("b", 1)),
                stepsOf(solver(costs).solve(birchFirst, both)));
    }

    @Test
    void costModelTriesTheCheapestProducerFirstAndTiesKeepCallerOrder() {
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gem");
        recipes.add(TestRecipe.of("gem_dear", "gem", 1, "dust"));
        recipes.add(TestRecipe.of("gem_cheap", "gem", 1, "dust"));
        MapCosts costs = new MapCosts().gather("dust", 1).recipe("gem_dear", 9).recipe("gem_cheap", 1);
        assertEquals(Arrays.asList(new PlannedStep<>("gem_dear", 1), new PlannedStep<>("ring", 1)),
                solveOk(ring, inv("dust", 1)));
        assertEquals(Arrays.asList(new PlannedStep<>("gem_cheap", 1), new PlannedStep<>("ring", 1)),
                stepsOf(solver(costs).solve(ring, inv("dust", 1))));
        // Equal costs: the caller's order stands (gem_b was listed first).
        recipes.clear();
        recipes.add(TestRecipe.of("gem_b", "gem", 1, "dust"));
        recipes.add(TestRecipe.of("gem_a", "gem", 1, "dust"));
        assertEquals(Arrays.asList(new PlannedStep<>("gem_b", 1), new PlannedStep<>("ring", 1)),
                stepsOf(solver(new MapCosts().gather("dust", 1)).solve(ring, inv("dust", 1))));
    }

    @Test
    void costModelShortfallGathersWhatIsNoDearerToGatherThanToCraftWhileSolveStillCraftsItFromStock() {
        recipes.add(TestRecipe.of("planks_recipe", "planks", 1, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1, "planks", "planks");
        // Crafting a plank costs 1 + the log's 3 = 4: gathering at 1 is cheaper, so planks are a leaf.
        RecipeGraphSolver<String, String> cheapPlanks = solver(new MapCosts().gather("log", 3).gather("planks", 1));
        assertEquals(inv("planks", 2), cheapPlanks.shortfall(chest, inv()).demands());
        assertEquals(1, cheapPlanks.unitCost("planks"));
        // The leaf rule only decides what to gather: logs in stock still become planks, held planks are used,
        // and what stock can make is not listed.
        assertEquals(Arrays.asList(new PlannedStep<>("planks_recipe", 2), new PlannedStep<>("chest", 1)),
                stepsOf(cheapPlanks.solve(chest, inv("log", 2))));
        assertEquals(Collections.emptyMap(), cheapPlanks.shortfall(chest, inv("log", 2)).demands());
        assertEquals(inv("planks", 1), cheapPlanks.shortfall(chest, inv("log", 1)).demands());
        assertInstanceOf(Result.Success.class, cheapPlanks.solve(chest, inv("planks", 2)));
        // Equal to the craft cost is still a leaf ("no more than"); one above it is crafted.
        assertEquals(inv("planks", 2), solver(new MapCosts().gather("log", 3).gather("planks", 4))
                .shortfall(chest, inv()).demands());
        assertEquals(inv("log", 2), solver(new MapCosts().gather("log", 3).gather("planks", 5))
                .shortfall(chest, inv()).demands());
    }

    @Test
    void costModelAnUnobtainableIngredientSteersThePlanToTheOtherProducer() {
        recipes.add(TestRecipe.of("gem_from_unobtanium", "gem", 1, "unobtanium"));
        recipes.add(TestRecipe.of("gem_from_dust", "gem", 1, "dust"));
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gem");
        Map<String, Integer> held = inv("unobtanium", 1, "dust", 1);
        assertEquals(Arrays.asList(new PlannedStep<>("gem_from_unobtanium", 1), new PlannedStep<>("ring", 1)),
                solveOk(ring, held));
        RecipeGraphSolver<String, String> solver = solver(new MapCosts().gather("dust", 2));
        assertEquals(Arrays.asList(new PlannedStep<>("gem_from_dust", 1), new PlannedStep<>("ring", 1)),
                stepsOf(solver.solve(ring, held)));
        assertEquals(3, solver.unitCost("gem"));
        assertEquals(inv("dust", 1), solver.shortfall(ring, inv()).demands());
        assertEquals(CostModel.UNOBTAINABLE, solver.unitCost("unobtanium"));
        // Tried last, not dropped: a caller whose stock holds the unobtainable ingredient still gets the plan.
        assertEquals(Arrays.asList(new PlannedStep<>("gem_from_unobtanium", 1), new PlannedStep<>("ring", 1)),
                stepsOf(solver.solve(ring, inv("unobtanium", 1))));
        // The same for a recipe whose own cost is unobtainable: it sorts last and stays usable.
        RecipeGraphSolver<String, String> dearRecipe = solver(new MapCosts().gather("dust", 2).gather("unobtanium", 1)
                .recipe("gem_from_unobtanium", CostModel.UNOBTAINABLE));
        assertEquals(Arrays.asList(new PlannedStep<>("gem_from_dust", 1), new PlannedStep<>("ring", 1)),
                stepsOf(dearRecipe.solve(ring, held)));
        assertEquals(Arrays.asList(new PlannedStep<>("gem_from_unobtanium", 1), new PlannedStep<>("ring", 1)),
                stepsOf(dearRecipe.solve(ring, inv("unobtanium", 1))));
    }

    @Test
    void costModelSubCraftsAnUnobtainableAlternativeOnlyAfterTheObtainableOnes() {
        recipes.add(TestRecipe.of("a_from_unobtanium", "item_a", 1, "unobtanium"));
        recipes.add(TestRecipe.of("b_from_dust", "item_b", 1, "dust"));
        TestRecipe thing = TestRecipe.of("thing", "thing", 1, "item_a|item_b");
        Map<String, Integer> held = inv("unobtanium", 1, "dust", 1);
        assertEquals(Arrays.asList(new PlannedStep<>("a_from_unobtanium", 1), new PlannedStep<>("thing", 1)),
                solveOk(thing, held));
        assertEquals(Arrays.asList(new PlannedStep<>("b_from_dust", 1), new PlannedStep<>("thing", 1)),
                stepsOf(solver(new MapCosts().gather("dust", 2)).solve(thing, held)));
        // Tried last, not dropped: with only the unobtainable route in stock, that route is the plan.
        assertEquals(Arrays.asList(new PlannedStep<>("a_from_unobtanium", 1), new PlannedStep<>("thing", 1)),
                stepsOf(solver(new MapCosts().gather("dust", 2)).solve(thing, inv("unobtanium", 1))));
    }

    @Test
    void costModelTriesUnobtainableRoutesLastEvenWhereTheCallersOrderWouldDeadEnd() {
        // p: the caller lists p_a first, which takes the only r1 the target's own slot needs; the cost model
        // tries the cheap p_b (r0) first. q, and the slot alternative qa, are made only from u, which cannot
        // be gathered but is held. The plan needs the cost model's order at p AND the unobtainable recipe at
        // q: dropping it fails, and so does the caller's order.
        recipes.add(TestRecipe.of("p_a", "p", 1, "r1"));
        recipes.add(TestRecipe.of("p_b", "p", 1, "r0"));
        recipes.add(TestRecipe.of("q_u", "q", 1, "u"));
        recipes.add(TestRecipe.of("q_w", "q", 1, "w")); // obtainable, but no w is held
        recipes.add(TestRecipe.of("qa_u", "qa", 1, "u"));
        recipes.add(TestRecipe.of("qb_w", "qb", 1, "w"));
        MapCosts costs = new MapCosts().gather("r1", 50).gather("r0", 1).gather("w", 1);
        Map<String, Integer> held = inv("r1", 1, "r0", 1, "u", 1);
        TestRecipe needsQ = TestRecipe.of("t", "t", 1, "p", "r1", "q");
        assertInstanceOf(Result.Failure.class, solver().solve(needsQ, held), "the caller's order dead-ends at p");
        assertEquals(Arrays.asList(new PlannedStep<>("p_b", 1), new PlannedStep<>("q_u", 1), new PlannedStep<>("t", 1)),
                stepsOf(solver(costs).solve(needsQ, held)));
        // The same through a slot: qb is cheaper but nothing to make it is held, so the unobtainable qa is used.
        TestRecipe needsQaOrQb = TestRecipe.of("t2", "t2", 1, "p", "r1", "qb|qa");
        assertInstanceOf(Result.Failure.class, solver().solve(needsQaOrQb, held));
        assertEquals(Arrays.asList(new PlannedStep<>("p_b", 1), new PlannedStep<>("qa_u", 1), new PlannedStep<>("t2", 1)),
                stepsOf(solver(costs).solve(needsQaOrQb, held)));
    }

    @Test
    void costModelFallsBackToTheCallersOrderWhereItsOwnOrderDeadEnds() {
        // The mirror case: the caller lists p_b (r0) first; the cost model prefers p_a, whose r1 the target's
        // own slot needs. The cost model's order finds no plan, so the caller's order, which does, is used.
        recipes.add(TestRecipe.of("p_b", "p", 1, "r0"));
        recipes.add(TestRecipe.of("p_a", "p", 1, "r1"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "p", "r1");
        Map<String, Integer> held = inv("r1", 1, "r0", 1);
        MapCosts costs = new MapCosts().gather("r1", 1).gather("r0", 50);
        assertEquals(Arrays.asList(new PlannedStep<>("p_b", 1), new PlannedStep<>("t", 1)), solveOk(target, held));
        assertEquals(Arrays.asList(new PlannedStep<>("p_b", 1), new PlannedStep<>("t", 1)),
                stepsOf(solver(costs).solve(target, held)));
        // Where the cost model's own order works it still wins: with a second r1 the cheap p_a is chosen.
        assertEquals(Arrays.asList(new PlannedStep<>("p_a", 1), new PlannedStep<>("t", 1)),
                stepsOf(solver(costs).solve(target, inv("r1", 2, "r0", 1))));
    }

    @Test
    void costModelTheExactPassSubCraftsTheCheapestAlternativeOfAShortSlotFirst() {
        // The oak-or-birch pair (a|b, a) defeats the slot-by-slot passes, so only the exact pass plans t; its
        // third slot is short and both p (from the dear rp) and q (from the cheap rq) can fill it. The caller
        // lists p first; the cost model must have the exact pass craft q.
        recipes.add(TestRecipe.of("p_recipe", "p", 1, "rp"));
        recipes.add(TestRecipe.of("q_recipe", "q", 1, "rq"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "a|b", "a", "p|q");
        Map<String, Integer> held = inv("a", 1, "b", 1, "rp", 1, "rq", 1);
        assertEquals(Arrays.asList(new PlannedStep<>("p_recipe", 1), new PlannedStep<>("t", 1)), solveOk(target, held));
        Result.Success<String, String> success = assertInstanceOf(Result.Success.class,
                solver(new MapCosts().gather("rp", 50).gather("rq", 1)).solve(target, held));
        assertEquals(Arrays.asList(new PlannedStep<>("q_recipe", 1), new PlannedStep<>("t", 1)), success.steps());
        assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held));
    }

    @Test
    void costModelANegativeMaxDepthAllowsNoRecipeAndFailsCleanly() {
        // The released solver tolerated a negative maxDepth (planning simply fails); a cost model must too:
        // no recipe may run, so an item costs what it costs to gather.
        recipes.add(TestRecipe.of("gem_recipe", "gem", 1, "dust"));
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gem");
        MapCosts costs = new MapCosts().gather("dust", 1).gather("gem", 9);
        for (int maxDepth : new int[] {-1, 0}) {
            RecipeGraphSolver<String, String> solver = solver(costs, maxDepth);
            assertEquals(9, solver.unitCost("gem"), "maxDepth " + maxDepth);
            assertEquals(CostModel.UNOBTAINABLE, solver.unitCost("ring"), "maxDepth " + maxDepth);
            assertInstanceOf(Result.Failure.class, solver.solve(ring, inv("dust", 1)), "maxDepth " + maxDepth);
            solver.shortfall(ring, inv("dust", 1)); // must not throw
        }
    }

    @Test
    void costModelShortfallSkipsUnobtainableAlternativesUnlessEveryOneIs() {
        TestRecipe thing = TestRecipe.of("thing", "thing", 1, "a|b");
        assertEquals(inv("b", 1), solver(new MapCosts().gather("b", 3)).shortfall(thing, inv()).demands());
        // Every alternative unobtainable: today's rule, the first alternative is reported.
        assertEquals(inv("a", 1), solver(new MapCosts()).shortfall(thing, inv()).demands());
    }

    @Test
    void costModelNeverPricesAnInventoryOnlyAlternativeByCraftingIt() {
        recipes.add(TestRecipe.of("make_bucket", "lava_bucket", 1, "iron"));
        recipes.add(TestRecipe.of("planks_recipe", "planks", 4, "log"));
        TestRecipe thing = TestRecipe.ofInventoryOnly("thing", "thing", 1,
                Collections.singletonList("lava_bucket"), "lava_bucket|planks");
        MapCosts costs = new MapCosts().gather("iron", 1).gather("log", 10);
        // The slot can hold a lava bucket but never make one, so the bucket is worth its gather cost
        // (unobtainable here), not the 2 that crafting it would cost; the planks route is what is left.
        assertEquals(inv("log", 1), solver(costs).shortfall(thing, inv()).demands());
        // With a gather cost it is a leaf to gather, cheaper than the planks (3).
        assertEquals(inv("lava_bucket", 1),
                solver(costs.gather("lava_bucket", 1)).shortfall(thing, inv()).demands());
    }

    @Test
    void costModelRejectsANegativeCost() {
        recipes.add(TestRecipe.of("gem_recipe", "gem", 1, "dust"));
        assertThrows(IllegalArgumentException.class,
                () -> solver(new MapCosts().gather("dust", -1)).unitCost("gem"));
        assertThrows(IllegalArgumentException.class,
                () -> solver(new MapCosts().gather("dust", 1).recipe("gem_recipe", -1)).unitCost("gem"));
    }

    @Test
    void costModelCompressionCycleTerminatesWithFiniteCostsForTheRawItem() {
        recipes.add(TestRecipe.of("ingots_from_block", "ingot", 9, "block"));
        recipes.add(TestRecipe.of("block_from_ingots", "block", 1, nine("ingot")));
        recipes.add(TestRecipe.of("ingot_from_ore", "ingot", 1, "ore"));
        RecipeGraphSolver<String, String> solver = solver(new MapCosts().gather("ore", 10));
        // ingot = 1 + 10, block = 1 + 9 x 11; unpacking the block (ceil(101 / 9) = 12) never beats the ore.
        assertEquals(10, solver.unitCost("ore"));
        assertEquals(11, solver.unitCost("ingot"));
        assertEquals(100, solver.unitCost("block"));
        TestRecipe pick = TestRecipe.of("pick", "pick", 1, "ingot", "ingot", "ingot");
        assertEquals(Arrays.asList(new PlannedStep<>("ingot_from_ore", 3), new PlannedStep<>("pick", 1)),
                stepsOf(solver.solve(pick, inv("ore", 3))));
        assertEquals(inv("ore", 3), solver.shortfall(pick, inv()).demands());
        // A cycle with nothing to gather in it is not free: both members stay unobtainable.
        RecipeGraphSolver<String, String> closed = solver(new MapCosts());
        assertEquals(CostModel.UNOBTAINABLE, closed.unitCost("ingot"));
        assertEquals(CostModel.UNOBTAINABLE, closed.unitCost("block"));
    }

    @Test
    void costModelResultCountDividesTheRecipeCostRoundingUp() {
        recipes.add(TestRecipe.of("bars", "bar", 4, "ore"));
        recipes.add(TestRecipe.of("rods", "rod", 3, "ore", "ore"));
        RecipeGraphSolver<String, String> solver = solver(new MapCosts().gather("ore", 10));
        assertEquals(3, solver.unitCost("bar"), "ceil((1 + 10) / 4)");
        assertEquals(7, solver.unitCost("rod"), "(1 + 20) / 3 divides exactly");
        assertEquals(4, solver(new MapCosts().gather("ore", 5)).unitCost("rod"), "ceil((1 + 10) / 3)");
    }

    @Test
    void costModelMultipliesASlotQuantityIntoTheRecipeCost() {
        recipes.add(TestRecipe.of("plate", "plate", 1, "ore").withSlotCounts(3));
        assertEquals(31, solver(new MapCosts().gather("ore", 10)).unitCost("plate"), "1 + 3 x 10");
    }

    @Test
    void costModelCountsTheStationsCostOncePerRecipeNotPerCraft() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        recipes.add(TestRecipe.of("table_recipe", "table", 1, "planks", "planks", "planks", "planks"));
        recipes.add(TestRecipe.of("bars", "bar", 2, "ore").withStation("table"));
        RecipeGraphSolver<String, String> solver = solver(new MapCosts().gather("log", 5).gather("ore", 4));
        // planks = ceil((1 + 5) / 4) = 2, table = 1 + 4 x 2 = 9, bar = ceil((1 + 4 + 9) / 2) = 7.
        assertEquals(2, solver.unitCost("planks"));
        assertEquals(9, solver.unitCost("table"));
        assertEquals(7, solver.unitCost("bar"));
    }

    @Test
    void costModelDepthBeyondMaxDepthIsUnobtainable() {
        for (int k = 0; k < 4; k++) {
            recipes.add(TestRecipe.of("r" + k, "item" + k, 1, "item" + (k + 1)));
        }
        MapCosts costs = new MapCosts().gather("item4", 1);
        // item0 needs four recipes in a row (cost 4 x 1 + 1), which maxDepth 4 allows and 3 does not.
        assertEquals(5, solver(costs, 4).unitCost("item0"));
        assertEquals(CostModel.UNOBTAINABLE, solver(costs, 3).unitCost("item0"));
        assertEquals(4, solver(costs, 3).unitCost("item1"));
    }

    @Test
    void costModelUnitCostDoesNotDependOnWhatWasAskedBefore() {
        for (int k = 0; k < 4; k++) {
            recipes.add(TestRecipe.of("r" + k, "item" + k, 1, "item" + (k + 1)));
        }
        MapCosts costs = new MapCosts().gather("item4", 1).gather("rawZ", 100);
        TestRecipe top = TestRecipe.of("top", "top", 1, "item0|rawZ");
        // item1 is three recipes from item4, inside maxDepth 3; item0 is four, beyond it, whatever was
        // priced first.
        RecipeGraphSolver<String, String> fresh = solver(costs, 3);
        assertEquals(CostModel.UNOBTAINABLE, fresh.unitCost("item0"));
        RecipeGraphSolver<String, String> warmed = solver(costs, 3);
        assertEquals(4, warmed.unitCost("item1"));
        assertEquals(CostModel.UNOBTAINABLE, warmed.unitCost("item0"));
        // And at plan level: the shortfall gathers the raw alternative either way.
        assertEquals(inv("rawZ", 1), solver(costs, 3).shortfall(top, inv()).demands());
        RecipeGraphSolver<String, String> warmedFirst = solver(costs, 3);
        warmedFirst.unitCost("item1");
        assertEquals(inv("rawZ", 1), warmedFirst.shortfall(top, inv()).demands());
    }

    @Test
    void costModelUnitCostsAreTheSameInAnyQueryOrder() {
        java.util.Random random = new java.util.Random(0x5C13B002L);
        for (int trial = 0; trial < 600; trial++) {
            randomGraph(random);
            MapCosts costs = randomCosts(random);
            int maxDepth = 1 + random.nextInt(4);
            String context = "trial " + trial + ": maxDepth " + maxDepth + ", recipes " + describe(recipes)
                    + ", gather " + costs.gather + ", recipe costs " + costs.recipe;
            Map<String, Long> fresh = new HashMap<>();
            for (String item : RANDOM_ITEMS) {
                fresh.put(item, solver(costs, maxDepth).unitCost(item));
            }
            List<String> order = new ArrayList<>(Arrays.asList(RANDOM_ITEMS));
            Collections.shuffle(order, random);
            List<String> reversed = new ArrayList<>(order);
            Collections.reverse(reversed);
            RecipeGraphSolver<String, String> one = solver(costs, maxDepth);
            RecipeGraphSolver<String, String> other = solver(costs, maxDepth);
            for (List<String> sequence : Arrays.asList(order, reversed)) {
                for (String item : sequence) {
                    assertEquals(fresh.get(item), one.unitCost(item), context + ", " + item + " asked in order " + order
                            + " then " + reversed);
                }
            }
            for (String item : reversed) {
                assertEquals(fresh.get(item), other.unitCost(item), context + ", " + item + " asked in order " + reversed);
            }
        }
    }

    @Test
    void costModelNeverMakesATargetFailThatTheSameStockCanMakeWithoutOne() {
        // Rule A: a cost model only orders producers and alternatives. Over random graphs, stocks and cost
        // models (ungatherable items, mids cheaper to gather than to craft, unobtainable recipe costs), solve
        // succeeds with the cost model whenever it succeeds without, and every plan replays under its own
        // layouts.
        java.util.Random random = new java.util.Random(0x5C13B001L);
        int trials = 1500;
        int solved = 0;
        for (int trial = 0; trial < trials; trial++) {
            RandomGraph graph = randomGraph(random);
            MapCosts costs = randomCosts(random);
            String context = "trial " + trial + ": target " + graph.target.ingredientSlots() + ", recipes "
                    + describe(recipes) + ", held " + graph.held + ", remainders " + remainders + ", gather "
                    + costs.gather + ", recipe costs " + costs.recipe;
            Result<String, String> plain = solver(null, 32, 1 << 16).solve(graph.target, graph.held);
            Result<String, String> costed = solver(costs, 32, 1 << 16).solve(graph.target, graph.held);
            // Only this direction is the rule: the search is greedy, so a cost model's order can also find a
            // valid plan the caller's order misses (a dear producer tried last leaves stock a cheap one needs).
            if (plain instanceof Result.Success) {
                assertInstanceOf(Result.Success.class, costed,
                        context + ": solvable without costs, but with costs " + costed);
            }
            if (costed instanceof Result.Success) {
                solved++;
                // The replay contract is the plan's own layouts (see randomGraphsOnlyYieldExecutablePlans):
                // PlanSimulator.replay re-decides each craft with SlotAssignment, which cannot look ahead and
                // rejects some default-rule plans too.
                Result.Success<String, String> success = (Result.Success<String, String>) costed;
                assertEquals(null, layoutInvariants(graph.target, success.steps(), success.layouts()),
                        context + ": malformed layouts " + success.layouts());
                assertEquals(null, replayLayoutsCraftByCraft(graph.target, success.steps(), success.layouts(),
                        graph.held), context + ": plan " + success.steps() + " does not replay under " + success.layouts());
            }
        }
        assertTrue(solved >= trials / 10, "only " + solved + " of " + trials + " random graphs were solvable");
    }

    @Test
    void gatheringAnExactShortfallMakesTheTargetSolvableWithOrWithoutACostModel() {
        // The randomized form of gatheringTheShortfallMakesTheRecipeSolvable, for the default rule and for
        // rule B: whatever shortfall lists without flagging it approximate, held plus that list solves.
        java.util.Random random = new java.util.Random(0x5C13B003L);
        int trials = 1000;
        int[] listed = new int[2];
        for (int trial = 0; trial < trials; trial++) {
            RandomGraph graph = randomGraph(random);
            MapCosts costs = randomCosts(random);
            for (int withCosts = 0; withCosts < 2; withCosts++) {
                RecipeGraphSolver<String, String> solver = solver(withCosts == 0 ? null : costs, 32, 1 << 16);
                String context = "trial " + trial + (withCosts == 0 ? " (no costs)" : " (costs)") + ": target "
                        + graph.target.ingredientSlots() + ", recipes " + describe(recipes) + ", held " + graph.held
                        + ", remainders " + remainders + ", gather " + costs.gather + ", recipe costs " + costs.recipe;
                RecipeGraphSolver.ShortfallResult<String> shortfall = solver.shortfall(graph.target, graph.held);
                if (shortfall.approximate() || shortfall.saturated()) {
                    continue;
                }
                Map<String, Integer> gathered = new HashMap<>(graph.held);
                shortfall.demands().forEach((item, count) -> gathered.merge(item, count, Integer::sum));
                assertInstanceOf(Result.Success.class, solver.solve(graph.target, gathered),
                        context + ": gathering " + shortfall.demands() + " must make the target solvable");
                if (!shortfall.demands().isEmpty()) {
                    listed[withCosts]++;
                }
            }
        }
        assertTrue(listed[0] >= trials / 10 && listed[1] >= trials / 10,
                "too few exact non-empty shortfalls: " + Arrays.toString(listed));
    }

    @Test
    void costModelSaturatesInsteadOfOverflowing() {
        recipes.add(TestRecipe.of("big", "big", 1, "ore", "ore"));
        recipes.add(TestRecipe.of("bigger", "bigger", 1, "big", "big", "big"));
        // 1 + 2 x (MAX / 4) still fits; three of those do not and must pin at unobtainable, not wrap.
        RecipeGraphSolver<String, String> solver = solver(new MapCosts().gather("ore", Long.MAX_VALUE / 4));
        assertEquals(1 + 2 * (Long.MAX_VALUE / 4), solver.unitCost("big"));
        assertEquals(CostModel.UNOBTAINABLE, solver.unitCost("bigger"));
    }

    @Test
    void costModelUnitCostNeedsACostModel() {
        assertThrows(IllegalStateException.class, () -> solver().unitCost("anything"));
    }

    @Test
    void explicitInterfaceDefaultsAndANullCostModelPlanExactlyLikeTheInheritedDefaults() {
        // Compares this solver with itself (recipes answering the defaults themselves through the cost-model
        // constructor, against the inherited defaults through the original one). Whether the defaults match the
        // released 2.1 behaviour is checked outside this suite, against a build of that release.
        java.util.Random random = new java.util.Random(0x5C100018L);
        int trials = 1500;
        int solved = 0;
        for (int trial = 0; trial < trials; trial++) {
            RandomGraph graph = randomGraph(random);
            String context = "trial " + trial + ": target " + graph.target.ingredientSlots() + ", recipes "
                    + describe(recipes) + ", held " + graph.held + ", remainders " + remainders;
            // As written: recipes on the interface defaults, the original constructor.
            RecipeGraphSolver<String, String> original = solver();
            Result<String, String> expected = original.solve(graph.target, graph.held);
            RecipeGraphSolver.ShortfallResult<String> expectedShortfall = original.shortfall(graph.target, graph.held);
            // The same graph with every recipe answering the defaults itself, through the new constructor.
            List<TestRecipe> explicit = new ArrayList<>();
            for (TestRecipe recipe : recipes) {
                explicit.add(recipe.withExplicitDefaults());
            }
            RecipeGraphSolver<String, String> wrapped = new RecipeGraphSolver<>(
                    item -> explicit.stream().filter(r -> r.result().equals(item)).collect(Collectors.toList()),
                    remainders::get, 32, 4096, RecipeGraphSolver.DEFAULT_MAX_STEPS, null);
            TestRecipe wrappedTarget = graph.target.withExplicitDefaults();
            assertSameResult(expected, wrapped.solve(wrappedTarget, graph.held), context);
            assertSameShortfall(expectedShortfall, wrapped.shortfall(wrappedTarget, graph.held), context);
            if (expected instanceof Result.Success) {
                solved++;
            }
        }
        assertTrue(solved >= trials / 10, "only " + solved + " of " + trials + " random graphs were solvable");
    }

    @Test
    void stationsQuantitiesAndCostsOnlyYieldPlansThatReplayUnderTheirOwnLayouts() {
        // The executability property over graphs that use all three additions: whatever solve() returns,
        // with or without a cost model, is well-formed and replays craft by craft under its own layouts
        // (a station held for every craft). shortfall() must also finish on these graphs.
        java.util.Random random = new java.util.Random(0x5C13D00DL);
        String[] raws = {"r0", "r1", "r2", "r3"};
        String[] mids = {"m0", "m1", "m2"};
        int[] yields = {1, 2, 3, 4, 9};
        int trials = 2000;
        int solved = 0;
        int solvedWithStation = 0;
        for (int trial = 0; trial < trials; trial++) {
            recipes.clear();
            remainders.clear();
            if (random.nextBoolean()) {
                recipes.add(TestRecipe.of("st_make", "st", 1 + random.nextInt(2),
                        randomSlot(random, raws, mids, 0), randomSlot(random, raws, mids, 0)));
            }
            for (int m = 0; m < mids.length; m++) {
                int variants = 1 + random.nextInt(2);
                for (int v = 0; v < variants; v++) {
                    String[] slots = new String[1 + random.nextInt(4)];
                    int[] counts = new int[slots.length];
                    for (int s = 0; s < slots.length; s++) {
                        slots[s] = randomSlot(random, raws, mids, random.nextInt(8) == 0 ? m + 1 : m);
                        counts[s] = random.nextInt(5) == 0 ? 2 : 1;
                    }
                    TestRecipe recipe = TestRecipe.of(mids[m] + "_" + v, mids[m],
                            yields[random.nextInt(yields.length)], slots).withSlotCounts(counts);
                    recipes.add(random.nextInt(4) == 0 ? recipe.withStation("st") : recipe);
                }
            }
            String[] targetSlots = new String[1 + random.nextInt(4)];
            int[] targetCounts = new int[targetSlots.length];
            for (int s = 0; s < targetSlots.length; s++) {
                targetSlots[s] = randomSlot(random, raws, mids, mids.length);
                targetCounts[s] = random.nextInt(5) == 0 ? 2 : 1;
            }
            TestRecipe target = TestRecipe.of("TARGET", "TARGET", 1, targetSlots).withSlotCounts(targetCounts);
            if (random.nextInt(3) == 0) {
                target = target.withStation("st");
            }
            Map<String, Integer> held = new HashMap<>();
            for (String raw : raws) {
                if (random.nextInt(3) > 0) {
                    held.put(raw, random.nextInt(11));
                }
            }
            if (random.nextInt(4) == 0) {
                held.put("st", 1 + random.nextInt(2));
            }
            MapCosts costs = new MapCosts();
            for (String raw : raws) {
                if (random.nextInt(8) != 0) {
                    costs.gather(raw, 1 + random.nextInt(9));
                }
            }
            for (String mid : mids) {
                if (random.nextInt(4) == 0) {
                    costs.gather(mid, 1 + random.nextInt(9));
                }
            }
            if (random.nextInt(3) == 0) {
                costs.gather("st", 1 + random.nextInt(9));
            }
            for (TestRecipe recipe : recipes) {
                costs.recipe(recipe.id(), random.nextInt(4));
            }
            for (int withCosts = 0; withCosts < 2; withCosts++) {
                RecipeGraphSolver<String, String> solver = withCosts == 0 ? solver() : solver(costs);
                String context = "trial " + trial + (withCosts == 0 ? " (no costs)" : " (costs)") + ": target "
                        + expandedSlots(target) + " station " + target.station() + ", recipes " + describe(recipes)
                        + ", held " + held;
                RecipeGraphSolver.ShortfallResult<String> shortfall = solver.shortfall(target, held);
                assertTrue(shortfall.demandsLong().values().stream().allMatch(count -> count > 0), context);
                Result<String, String> result = solver.solve(target, held);
                if (!(result instanceof Result.Success)) {
                    continue;
                }
                Result.Success<String, String> success = (Result.Success<String, String>) result;
                solved++;
                for (PlannedStep<String> step : success.steps()) {
                    if (byId(target, step.recipeId()).station() != null) {
                        solvedWithStation++;
                        break;
                    }
                }
                assertEquals(null, layoutInvariants(target, success.steps(), success.layouts()),
                        context + " has malformed layouts " + success.layouts());
                assertEquals(null, replayLayoutsCraftByCraft(target, success.steps(), success.layouts(), held),
                        context + ": plan " + success.steps() + " does not replay under " + success.layouts());
            }
        }
        assertTrue(solved >= trials / 5, "only " + solved + " solves in " + 2 * trials + " random runs");
        assertTrue(solvedWithStation >= trials / 20, "only " + solvedWithStation + " plans involved a station");
    }
}
