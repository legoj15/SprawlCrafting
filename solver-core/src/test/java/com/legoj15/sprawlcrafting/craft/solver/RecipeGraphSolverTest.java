package com.legoj15.sprawlcrafting.craft.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver.RecipeInfo;
import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGraphSolverTest {

    /** String-typed recipe for tests: id, slots (each "a|b" = alternatives), result, count. */
    private static final class TestRecipe implements RecipeInfo<String, String> {
        private final String id;
        private final List<List<String>> ingredientSlots;
        private final String result;
        private final int resultCount;

        TestRecipe(String id, List<List<String>> ingredientSlots, String result, int resultCount) {
            this.id = id;
            this.ingredientSlots = ingredientSlots;
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
        public String result() {
            return result;
        }

        @Override
        public int resultCount() {
            return resultCount;
        }

        static TestRecipe of(String id, String result, int resultCount, String... slots) {
            List<List<String>> parsed = new ArrayList<>();
            for (String slot : slots) {
                parsed.add(Arrays.asList(slot.split("\\|")));
            }
            return new TestRecipe(id, parsed, result, resultCount);
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
        // X has resultCount 1 (no surplus to reuse), and Y sits between the two X crafts.
        recipes.add(TestRecipe.of("x", "x", 1, "rawx"));
        recipes.add(TestRecipe.of("y", "y", 1, "rawy"));
        TestRecipe target = TestRecipe.of("t", "t", 1, "x", "y", "x");
        assertEquals(Arrays.asList(
                        new PlannedStep<>("x", 1),
                        new PlannedStep<>("y", 1),
                        new PlannedStep<>("x", 1),
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
        recipes.add(TestRecipe.of("plank", "plank", 1, "log"));
        TestRecipe wall = TestRecipe.of("wall", "wall", 1,
                "plank", "plank", "plank", "plank", "plank", "plank");
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
}
