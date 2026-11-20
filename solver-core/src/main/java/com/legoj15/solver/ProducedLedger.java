package com.legoj15.solver;

import java.util.HashMap;
import java.util.Map;

/**
 * What a deferred-craft job has produced and not yet used back up, per item identity. The executor
 * refuses damaged, enchanted, or renamed stacks so it never eats the player's own gear, but some
 * mods alter an item after it lands in the inventory (an inventory-tick hook that enchants every
 * copy), which would make a job refuse the intermediate it just crafted. A stack the filter rejects
 * may still be taken while this ledger has an outstanding count for its identity; each such take is
 * {@linkplain #consumed recorded} against it. The executor runs each craft through a
 * {@link LedgerTally}, which admits rejected copies only above those of the same exact variant
 * ({@code V}: item + damage/meta + components/NBT) the pool held before the job produced anything
 * (the player's own, kept here as {@link #preexisting}), and also records plain takes of an identity
 * beyond the job's rejected copies still held, so a count does not outlive the copies it described.
 * The vanilla final hand-off is exempt: it never takes rejected stacks and ends the job.
 *
 * <p>Outstanding counts are keyed by identity rather than by the exact stack produced because the
 * alteration happens after production: the stack recorded at insertion and the stack found later no
 * longer compare equal. The player's-own baseline is keyed by variant instead: the player's altered
 * copy of one variant says nothing about the job's altered copies of another, and freezing it per
 * variant keeps the baseline right even if the player moves their own copy out mid-job.
 */
public final class ProducedLedger<K, V> {

    private final Map<K, Long> outstanding = new HashMap<>();
    private final Map<V, Integer> preexisting = new HashMap<>();
    private boolean producedAny;

    public void produced(K key, long count) {
        if (count <= 0) {
            return;
        }
        producedAny = true;
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

    /**
     * Filter-rejected copies of this exact variant the pool held before this job produced anything:
     * the player's own, never admitted as the job's.
     */
    public long preexisting(V variant) {
        Integer count = preexisting.get(variant);
        return count == null ? 0 : count;
    }

    /** Replaces the player's-own snapshot (per variant) with {@code refusedHeld}; the job's first production freezes it. */
    void notePreexisting(Map<V, Integer> refusedHeld) {
        if (!producedAny) {
            preexisting.clear();
            preexisting.putAll(refusedHeld);
        }
    }
}
