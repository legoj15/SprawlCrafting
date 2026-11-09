package com.legoj15.sprawlcrafting.craft.solver;

import java.util.HashMap;
import java.util.Map;

/**
 * What a deferred-craft job has produced and not yet used back up, per item identity. The executor
 * refuses damaged, enchanted, or renamed stacks so it never eats the player's own gear, but some
 * mods alter an item after it lands in the inventory (an inventory-tick hook that enchants every
 * copy), which would make a job refuse the intermediate it just crafted. A stack the filter rejects
 * may still be taken while this ledger has an outstanding count for its identity; each such take is
 * {@linkplain #consumed recorded} against it. Plain takes of an identity with an outstanding count
 * are recorded too, so a count never outlives the copies it described.
 *
 * <p>Keyed by identity rather than by the exact stack produced because the alteration happens
 * after production: the stack recorded at insertion and the stack found later no longer compare
 * equal. A player-owned altered stack of the same identity is therefore usable too, but only up to
 * the count this job made.
 */
public final class ProducedLedger<K> {

    private final Map<K, Long> outstanding = new HashMap<>();

    public void produced(K key, long count) {
        if (count <= 0) {
            return;
        }
        long current = outstanding(key);
        outstanding.put(key, current > Long.MAX_VALUE - count ? Long.MAX_VALUE : current + count);
    }

    public long outstanding(K key) {
        Long count = outstanding.get(key);
        return count == null ? 0 : count;
    }

    /** Whether one more filter-rejected stack may be taken when {@code alreadyClaimed} are pending this craft. */
    public boolean canTake(K key, long alreadyClaimed) {
        return outstanding(key) > alreadyClaimed;
    }

    public void consumed(K key, long count) {
        long remaining = outstanding(key) - count;
        if (remaining > 0) {
            outstanding.put(key, remaining);
        } else {
            outstanding.remove(key);
        }
    }
}
