package com.legoj15.sprawlcrafting.craft;

import java.util.Collections;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * The item storage behind a crafting station the player has open right now (issue #29, the 1.16.5 port of
 * #2: an AE2 Crafting Terminal shows its ME network, so while it is open the network is craft material).
 * Design rule: a craft only ever draws from what is visible in the open screen, so an instance is valid
 * for the moment it was obtained from {@link StationStorages#open}; callers re-open it for every craft
 * instead of holding it across ticks.
 *
 * <p>The plain methods ({@link #counts()}, {@link #available}, {@link #extract}, {@link #insert},
 * {@link #acceptable}) stay plain-only: one stack identity per {@link Item}, the one a freshly made stack
 * of it has (see the provider's {@code canonical}), and {@link #insert} refuses anything else. A plain
 * stack always passes {@code CraftExecutor.usableAsIngredient} (no damage, enchantment or name), so the
 * planner's snapshot, the executor's draws and the client's mirror all agree on the same pool, keyed by
 * item exactly like the player-inventory snapshot. The protected methods ({@link #protectedCounts()},
 * {@link #protectedSamples()}, {@link #extractProtected}) serve only the player-confirmed protected tier
 * (issue #40, widened to the station in #49): enchanted/renamed, never damaged, copies the station shows,
 * counted per item and drawn identity-exact — never mixed into the plain pool, whose methods keep
 * behaving as if they did not exist.
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

    /**
     * Protected-stack counts (issue #49): per {@link Item}, how many protected stacks
     * ({@code CraftExecutor.isProtected}: enchanted and/or renamed, never damaged) the station holds,
     * shows in its view filter and could extract right now — the confirmed tier's mirror of
     * {@link #counts}. {@link Long#MAX_VALUE} means unbounded, by the same rule as {@link #counts()}
     * (probe the exact identity, as in {@code unboundedIfProbed}). These counts never feed the plain
     * pool: the planner merges them only into its protected pass. May be expensive (a full storage
     * scan), so it is read once per plan, never per craft. Default: none (a station without a
     * protected tier).
     */
    default Map<Item, Long> protectedCounts() {
        return Collections.emptyMap();
    }

    /**
     * One count-1 copy per item of {@link #protectedCounts()} (hover name and enchantments intact),
     * for the confirmation warning lines (issue #49). Default: none.
     */
    default Map<Item, ItemStack> protectedSamples() {
        return Collections.emptyMap();
    }

    /**
     * Extracts exactly ONE protected stack (a count-1 copy) of {@code item} whose exact identity
     * {@code accepts} tests true (issue #49) — identity-exact: an implementation must build its
     * request from the stored entry's own copy with stack size 1, never from the canonical plain key
     * (that would draw a plain stack instead; {@link #extract} stays plain-only). MODULATE, with
     * power. Default: {@link ItemStack#EMPTY} (nothing to extract).
     *
     * @return the extracted stack, or {@link ItemStack#EMPTY} when the station holds no protected copy
     *         of {@code item} that {@code accepts} accepts
     */
    default ItemStack extractProtected(Item item, Predicate<ItemStack> accepts) {
        return ItemStack.EMPTY;
    }
}
