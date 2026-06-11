package com.legoj15.sprawlcrafting.craft.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver.RecipeInfo;
import com.legoj15.sprawlcrafting.craft.solver.RecipeGraphSolver.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGraphSolverTest {

    /** String-typed recipe for tests: id, slots (each "a|b" = alternatives), result, count. */
    private record TestRecipe(String id, List<List<String>> ingredientSlots, String result,
                              int resultCount) implements RecipeInfo<String, String> {

        static TestRecipe of(String id, String result, int resultCount, String... slots) {
            List<List<String>> parsed = new ArrayList<>();
            for (String slot : slots) {
                parsed.add(List.of(slot.split("\\|")));
            }
            return new TestRecipe(id, parsed, result, resultCount);
        }
    }

    private final List<TestRecipe> recipes = new ArrayList<>();
    private final Map<String, String> remainders = new HashMap<>();

    private RecipeGraphSolver<String, String> solver() {
        return new RecipeGraphSolver<>(
                item -> recipes.stream().filter(r -> r.result().equals(item)).toList(),
                remainders::get,
                32, 4096);
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
        assertEquals(List.of(new PlannedStep<>("stick", 1)),
                solveOk(stick, inv("planks", 2)));
    }

    @Test
    void missingIntermediateIsSubCrafted() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        // 8 planks needed, none in stock: 2 plank crafts (4 each) from 2 logs, then the chest.
        assertEquals(List.of(new PlannedStep<>("planks", 2), new PlannedStep<>("chest", 1)),
                solveOk(chest, inv("log", 2)));
    }

    @Test
    void stockIsUsedBeforeSubCrafting() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        TestRecipe chest = TestRecipe.of("chest", "chest", 1,
                "planks", "planks", "planks", "planks", "planks", "planks", "planks", "planks");
        // 6 planks in stock + one craft of 4 covers the remaining 2.
        assertEquals(List.of(new PlannedStep<>("planks", 1), new PlannedStep<>("chest", 1)),
                solveOk(chest, inv("planks", 6, "log", 1)));
    }

    @Test
    void deepChainResolvesInTopologicalOrder() {
        recipes.add(TestRecipe.of("planks", "planks", 4, "log"));
        recipes.add(TestRecipe.of("stick", "stick", 4, "planks", "planks"));
        TestRecipe pickaxe = TestRecipe.of("pickaxe", "pickaxe", 1,
                "cobble", "cobble", "cobble", "stick", "stick");
        assertEquals(List.of(
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
        assertEquals(List.of(new PlannedStep<>("birch_planks", 1), new PlannedStep<>("stick", 1)),
                solveOk(stick, inv("birch_log", 1)));
    }

    @Test
    void stockedAlternativeBeatsCraftablePreferredOne() {
        recipes.add(TestRecipe.of("oak_planks", "oak_planks", 4, "oak_log"));
        TestRecipe stick = TestRecipe.of("stick", "stick", 4,
                "oak_planks|birch_planks", "oak_planks|birch_planks");
        // Birch planks already in stock: no oak crafting even though oak is listed first.
        assertEquals(List.of(new PlannedStep<>("stick", 1)),
                solveOk(stick, inv("birch_planks", 2, "oak_log", 1)));
    }

    @Test
    void firstSatisfiableProducerWins() {
        recipes.add(TestRecipe.of("gem_from_ore", "gem", 1, "ore"));
        recipes.add(TestRecipe.of("gem_from_dust", "gem", 1, "dust", "dust"));
        TestRecipe ring = TestRecipe.of("ring", "ring", 1, "gem");
        // No ore: the first producer fails, the second resolves.
        assertEquals(List.of(new PlannedStep<>("gem_from_dust", 1), new PlannedStep<>("ring", 1)),
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
        assertEquals(List.of(new PlannedStep<>("ingots_from_block", 1), new PlannedStep<>("pick", 1)),
                solveOk(pick, inv("block", 1)));
    }

    @Test
    void remainderItemsReturnToInventory() {
        remainders.put("milk_bucket", "bucket");
        TestRecipe cake = TestRecipe.of("cake", "cake", 1,
                "milk_bucket", "milk_bucket", "milk_bucket", "wheat", "wheat", "wheat");
        List<PlannedStep<String>> steps = solveOk(cake, inv("milk_bucket", 3, "wheat", 3));
        assertEquals(List.of(new PlannedStep<>("cake", 1)), steps);
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
        assertEquals(List.of(new PlannedStep<>("cake", 1), new PlannedStep<>("gift", 1)),
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
        assertEquals(List.of(new PlannedStep<>("planks", 3), new PlannedStep<>("bundle", 1)),
                solveOk(bundle, inv("log", 3)));
    }

    @Test
    void resultCountSurplusCarriesAcrossSlots() {
        recipes.add(TestRecipe.of("stick", "stick", 4, "planks", "planks"));
        TestRecipe frame = TestRecipe.of("frame", "frame", 1,
                "stick", "stick", "stick", "stick", "stick", "stick");
        // 6 sticks needed: two crafts of 4 (one would only give 4), planks from stock.
        assertEquals(List.of(new PlannedStep<>("stick", 2), new PlannedStep<>("frame", 1)),
                solveOk(frame, inv("planks", 4)));
    }
}
