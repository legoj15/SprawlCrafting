package com.legoj15.solver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One craft's bookkeeping against a {@link ProducedLedger}: which identities the pool holds
 * filter-rejected copies of, and which stacks the craft picked, rejected or plain. The executor
 * fills it while choosing slots and {@linkplain #chargeTo charges} it once the craft is applied.
 *
 * <p>A rejected pick needs ledger headroom: it is admitted only while
 * {@code ledger.canTake(key, refusedTakes().get(key))}, so admissions never exceed what the job
 * produced. A plain pick needs none, but it may be the job's own copy, and leaving its count behind
 * would later admit a player-owned altered copy instead (a stale count). So at charge time the
 * count is kept back only up to the rejected copies of that identity the pool still holds after
 * this craft, and plain picks drain anything beyond that. That one rule covers both a plain-only
 * craft while the player's own altered copy is held and a craft that took the rejected copy and
 * plain copies together. A plain pick never spends headroom during the craft, so it cannot block a
 * rejected pick the same craft needs, and the total charged never exceeds the outstanding count.
 */
public final class LedgerTally<K> {

    private final Map<K, Integer> refusedHeld = new HashMap<>();
    private final Map<K, Integer> refusedTakes = new HashMap<>();
    private final Map<K, Integer> plainTakes = new HashMap<>();

    /** The pool holds {@code count} more filter-rejected items of {@code key}. Call before picking. */
    public void held(K key, int count) {
        if (count > 0) {
            add(refusedHeld, key, count);
        }
    }

    /** One item of {@code key} was picked for this craft; {@code refused}: the filter rejects it. */
    public void picked(K key, boolean refused) {
        add(refused ? refusedTakes : plainTakes, key, 1);
    }

    /** Live per-identity count of rejected picks so far: the {@code alreadyClaimed} for {@link ProducedLedger#canTake}. */
    public Map<K, Integer> refusedTakes() {
        return refusedTakes;
    }

    /** Records this craft's picks against {@code ledger}. */
    public void chargeTo(ProducedLedger<K> ledger) {
        Set<K> keys = new HashSet<>(refusedTakes.keySet());
        keys.addAll(plainTakes.keySet());
        for (K key : keys) {
            long outstanding = ledger.outstanding(key);
            if (outstanding <= 0) {
                continue;
            }
            long refused = get(refusedTakes, key);
            long plain = get(plainTakes, key);
            long held = get(refusedHeld, key);
            // Headroom stays reserved only for the rejected copies still held after this craft
            // (held - refused); plain picks drain whatever count exceeds that. The ledger is keyed
            // by identity, so when count <= copies held it cannot tell the job's altered copy from
            // the player's and keeps the count rather than strand the job's own intermediate.
            // (outstanding - refused) - (held - refused) reduces to outstanding - held.
            long plainDrain = Math.min(plain, Math.max(0, outstanding - held));
            ledger.consumed(key, refused + plainDrain);
        }
    }

    private static <K> void add(Map<K, Integer> map, K key, int count) {
        Integer current = map.get(key);
        map.put(key, current == null ? count : current + count);
    }

    private static <K> int get(Map<K, Integer> map, K key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }
}
