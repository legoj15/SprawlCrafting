package com.legoj15.sprawlcrafting.craft;

import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * The item storage behind a crafting station the player has open right now (issue #29, the 1.16.5 port of
 * #2: an AE2 Crafting Terminal shows its ME network, so while it is open the network is craft material).
 * Design rule: a craft only ever draws from what is visible in the open screen, so an instance is valid
 * for the moment it was obtained from {@link StationStorages#open}; callers re-open it for every craft
 * instead of holding it across ticks.
 *
 * <p>Only <em>plain</em> items count: one stack identity per {@link Item}, the one a freshly made stack
 * of it has (see the provider's {@code canonical}). A plain stack always passes
 * {@code CraftExecutor.usableAsIngredient} (no damage, enchantment or name), so the planner's snapshot,
 * the executor's draws and the client's mirror all agree on the same pool, keyed by item exactly like
 * the player-inventory snapshot — whose protected tier (issue #40) adds only enchanted/renamed copies
 * the player holds, never anything from here: the station stays a plain pool even for a job planned
 * with protected stacks.
 *
 * <p>Implementations live in loader/mod compat code ({@code compat/ae2}, Forge only); shared code only
 * ever sees this interface.
 */
public interface StationStorage {

    /**
     * Plain-item counts for the planner's snapshot: what {@link #extract} could really deliver right now
     * (a storage that can be seen but not drawn from, e.g. a network without the power to move the items,
     * counts for nothing), and empty when the station is not usable. {@link Long#MAX_VALUE} means
     * unbounded — a source that behaves as infinite (an AE2 creative cell, proved by a probe, not by a
     * big listing); every other value is that finite count, however far above an int a real storage
     * holds (issue #47). May be expensive (a full storage scan), so it is called once per plan, never
     * per craft.
     */
    Map<Item, Long> counts();

    /** How many plain {@code item} could be extracted right now, at most {@code limit} (simulated, no change). */
    long available(Item item, long limit);

    /**
     * Extracts up to {@code count} plain {@code item}.
     *
     * @return exactly how many were removed from the storage (0..count). The caller owns them: it must
     *         consume or give back every one, so a short extraction never voids or dupes.
     */
    int extract(Item item, int count);

    /**
     * Inserts as much of {@code stack} as the storage accepts. Does not modify the argument. The stack may
     * be far over its max stack size (a batched craft's whole overflow, up to Integer.MAX_VALUE items);
     * the storage must take it by count, not as one slot's worth.
     *
     * @return what was not accepted ({@link ItemStack#EMPTY} when all of it went in)
     */
    ItemStack insert(ItemStack stack);

    /**
     * How many of {@code stack}'s item and tag {@link #insert} would accept right now, at most
     * {@code limit} (simulated, no change). A batched craft bounds its output by this so it never plans
     * to drop items at the player's feet.
     */
    long acceptable(ItemStack stack, long limit);
}
