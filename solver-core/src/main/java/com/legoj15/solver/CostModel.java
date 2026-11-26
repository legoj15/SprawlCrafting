package com.legoj15.solver;

/**
 * What an item costs to obtain, so a planner that starts from an empty inventory can choose the
 * cheapest way to gather instead of the first way the recipe list happens to offer. Pass one to the
 * {@code RecipeGraphSolver} constructor that takes a cost model; without one the solver keeps its
 * default rule (producers and slot alternatives in the order the caller lists them), which is the
 * right one for a player crafting from a full inventory.
 *
 * <p>Costs are abstract non-negative numbers (seconds, trips, blocks to mine); only their order and
 * sums matter. The solver derives from them a <em>unit cost</em> per item: the smaller of
 * {@link #gatherCost} and the cheapest way to craft one unit (see
 * {@link RecipeGraphSolver#unitCost}). That figure orders choices and nothing else: which producer a
 * plan tries first and which alternative of an ingredient slot it fills first once stock has been
 * used (cheapest first, {@link #UNOBTAINABLE} last but still tried), and, for what stock cannot cover,
 * whether a shortfall lists an item to gather or the ingredients to craft it from. It never makes a
 * target fail that the same stock can make without a cost model: stock always comes first, and
 * nothing is removed from consideration because of its cost.
 *
 * <p>Costs are derived once per item and kept for the solver's lifetime, ignoring stock: an item
 * the inventory holds still has its full gather cost here. An implementation must therefore answer
 * the same for the same item and recipe every time. {@link #UNOBTAINABLE} is safe for an item that
 * may be held but not gathered (a creative-only block a chest contains): a recipe that needs it is
 * tried last, and used when stock holds what it needs. A consumer that wants a recipe gone leaves
 * it out of the solver's {@code producers} instead.
 *
 * @param <R> recipe identifier type
 * @param <K> item identity type
 */
public interface CostModel<R, K> {

    /** Marks an item that cannot be gathered at all. */
    long UNOBTAINABLE = Long.MAX_VALUE;

    /**
     * Cost of gathering one unit of the item from outside the recipe graph (mining it, killing it,
     * buying it), or {@link #UNOBTAINABLE}. Never negative. An item the graph can craft may still
     * answer here: a shortfall lists it to gather, for what stock cannot make, when that is no dearer
     * than crafting it. A plan still crafts it from stock.
     */
    long gatherCost(K item);

    /**
     * Cost of running the recipe once, on top of its ingredients (time at the station, fuel).
     * Never negative; {@link #UNOBTAINABLE} sorts the recipe after every other producer of its item
     * but does not make it unusable (leave it out of {@code producers} for that). The default
     * charges 1 per craft, so a longer chain always costs more than a shorter one made of the same
     * raw items.
     */
    default long recipeCost(RecipeGraphSolver.RecipeInfo<R, K> recipe) {
        return 1;
    }
}
