package com.legoj15.sprawlcrafting.craft;

import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves a requested recipe into a {@link CraftJob} by walking the recipe dependency
 * tree against the player's current inventory.
 *
 * <p>Resolution rules (see DESIGN.md):
 * <ul>
 *   <li>Items already in the inventory are used as-is before any sub-crafting is planned.</li>
 *   <li>A missing ingredient is satisfiable if some crafting recipe produces it and that
 *       recipe's own ingredients are (recursively) satisfiable. The search is depth-limited
 *       and cycle-guarded.</li>
 *   <li>When several recipes produce the same missing ingredient, the first one whose raw
 *       cost is currently satisfiable wins, preferring fewer total steps (deterministic).</li>
 *   <li>The resulting step list is a topological order of the dependency tree, so each
 *       step only needs items that exist by the time it runs.</li>
 * </ul>
 */
public final class CraftPlanner {

    private CraftPlanner() {
    }

    /**
     * Plans a deferred craft of {@code targetRecipe} for {@code player}.
     *
     * @return the planned job, or empty if the target is not satisfiable from the
     *         player's current raw resources
     */
    public static Optional<CraftJob> plan(ServerPlayer player, ResourceLocation targetRecipe) {
        // TODO: walk RecipeManager for crafting recipes producing each missing ingredient,
        //  simulate inventory consumption, and emit a topologically ordered step list.
        return Optional.empty();
    }
}
