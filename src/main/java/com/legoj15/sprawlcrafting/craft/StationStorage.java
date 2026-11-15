package com.legoj15.sprawlcrafting.craft;

import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The item storage behind a crafting station the player has open right now (issue #2: an AE2
 * Crafting Terminal shows its ME network, so while it is open the network is craft material).
 * Design rule: a craft only ever draws from what is visible in the open screen, so an instance is
 * valid for the moment it was obtained from {@link StationStorages#open}; callers re-open it for
 * every craft instead of holding it across ticks.
 *
 * <p>Only <em>plain</em> items count: one stack identity per {@link Item} with no component patch.
 * A plain stack always passes {@code CraftExecutor.usableAsIngredient} (it carries no damage,
 * enchantment or name), so the planner's snapshot, the executor's draws and the client's mirror all
 * agree on the same pool, keyed by item exactly like the player-inventory snapshot.
 *
 * <p>Implementations live in loader/mod compat code ({@code compat/ae2}); shared code only ever
 * sees this interface.
 */
public interface StationStorage {

    /**
     * Plain-item counts for the planner's snapshot. May be expensive (a full storage scan), so it is
     * called once per plan, never per craft.
     */
    Map<Item, Long> counts();

    /** How many plain {@code item} could be extracted right now, at most {@code limit} (simulated, no change). */
    long available(Item item, long limit);

    /**
     * Extracts up to {@code count} plain {@code item}.
     *
     * @return exactly how many were removed from the storage (0..count). The caller owns them: it
     *         must consume or give back every one, so a short extraction never voids or dupes.
     */
    int extract(Item item, int count);

    /**
     * Inserts as much of {@code stack} as the storage accepts. Does not modify the argument.
     *
     * @return what was not accepted ({@link ItemStack#EMPTY} when all of it went in)
     */
    ItemStack insert(ItemStack stack);
}
