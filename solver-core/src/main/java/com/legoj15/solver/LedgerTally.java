package com.legoj15.solver;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One craft's bookkeeping against a {@link ProducedLedger}: which exact variants ({@code V}: item +
 * damage/meta + components/NBT) the player holds filter-rejected copies of, and which stacks the craft
 * picked, rejected or plain. The executor records the rejected copies the player holds, opens the craft
 * with {@link #open}, asks {@link #admits} while choosing slots, records each pick with
 * {@link #picked}, and {@linkplain #chargeTo charges} the tally once the craft is applied.
 *
 * <p>Rejected copies the player held before the job produced anything are the player's own, and the
 * ledger keeps that count per variant (the tallies opened before any production record it). {@code held}
 * spans the player's holdings the executor can see — the slots it picks from plus any it may not
 * (offhand, armour, cursor, a crafting grid outside the pool) — which is what makes moving a stack
 * between them neutral. Only rejected copies above the baseline can be the job's: a rejected pick of a variant is
 * admitted while {@link #admits} says so, which caps the job's count at those copies per variant and
 * per identity, so the player's own altered copies held when the job started are never admitted and
 * admissions never exceed what the job produced. A player discarding (dropping, storing in a chest) a
 * copy of a variant the job also produced makes that many of the job's copies of that variant
 * inadmissible — a safe stall — while a discarded copy of any other variant affects nothing. What
 * remains indistinguishable from the job's altered copies is a player-owned copy of exactly the same
 * variant — interchangeable with the job's in every observable way — and altered copies that enter the
 * holdings counted in {@code held} only after the job's first production (a pickup, an item altered
 * mid-job, a copy taken out of storage the executor does not count); both
 * stay bounded by the produced count.
 *
 * <p>A plain pick needs no headroom, but it may be the job's own copy, and leaving its count behind
 * is a stale count. So at charge time the count is kept back only for the job's rejected copies the
 * player still holds after this craft, and plain picks drain anything beyond that. That one rule
 * covers both a plain-only craft while the player's own altered copy is held and a craft that took
 * the rejected copy and plain copies together. A plain pick never spends headroom during the craft,
 * so it cannot block a rejected pick the same craft needs, and the total charged never exceeds the
 * outstanding count.
 */
public final class LedgerTally<K, V> {

    private final Map<V, Integer> refusedHeld = new HashMap<>();
    /** Each identity's variants seen held, to sum over them when admission or charging is per identity. */
    private final Map<K, Set<V>> variantsOf = new HashMap<>();
    private final Map<V, Integer> refusedTakes = new HashMap<>();
    private final Map<K, Integer> refusedTakesByKey = new HashMap<>();
    private final Map<K, Integer> plainTakes = new HashMap<>();
    private ProducedLedger<K, V> ledger;

    /**
     * The player holds {@code count} more filter-rejected copies of this exact {@code variant}, in the
     * pool or in any other holding. Call before {@link #open}.
     */
    public void held(K key, V variant, int count) {
        if (count > 0) {
            add(refusedHeld, variant, count);
            variantsOf.computeIfAbsent(key, k -> new HashSet<>()).add(variant);
        }
    }

    /**
     * Opens this craft against {@code ledger}, which {@link #admits} then answers against. Until the
     * job first produces something, this also records the rejected copies held per variant as the
     * player's own.
     */
    public void open(ProducedLedger<K, V> ledger) {
        ledger.notePreexisting(refusedHeld);
        this.ledger = ledger;
    }

    /**
     * Whether one more filter-rejected pick of exactly this {@code variant} is admitted: per variant,
     * while this craft's rejected takes of it are below the copies held above the player's own, and
     * per identity, while they are below both the ledger's outstanding count and that identity's
     * job-held copies summed over its variants.
     */
    public boolean admits(K key, V variant) {
        if (ledger == null || get(refusedTakes, variant) >= jobHeld(variant)) {
            return false;
        }
        long jobHeldByKey = 0;
        for (V held : variantsOf.getOrDefault(key, Collections.<V>emptySet())) {
            jobHeldByKey += jobHeld(held);
        }
        return get(refusedTakesByKey, key) < Math.min(ledger.outstanding(key), jobHeldByKey);
    }

    /**
     * One item of this exact {@code variant} was picked for this craft; {@code refused}: the filter
     * rejects it. A plain pick's variant is ignored.
     */
    public void picked(K key, V variant, boolean refused) {
        if (refused) {
            add(refusedTakes, variant, 1);
            add(refusedTakesByKey, key, 1);
        } else {
            add(plainTakes, key, 1);
        }
    }

    /** Records this craft's picks against {@code ledger}. */
    public void chargeTo(ProducedLedger<K, V> ledger) {
        ledger.notePreexisting(refusedHeld);
        Set<K> keys = new HashSet<>(refusedTakesByKey.keySet());
        keys.addAll(plainTakes.keySet());
        for (K key : keys) {
            long outstanding = ledger.outstanding(key);
            if (outstanding <= 0) {
                continue;
            }
            long refused = get(refusedTakesByKey, key);
            long plain = get(plainTakes, key);
            // The count stays reserved only for the job's rejected copies still held after this
            // craft; plain picks drain whatever the count left after the rejected picks exceeds that.
            long keep = 0;
            for (V variant : variantsOf.getOrDefault(key, Collections.<V>emptySet())) {
                keep += Math.max(0, get(refusedHeld, variant) - get(refusedTakes, variant)
                        - ledger.preexisting(variant));
            }
            long plainDrain = Math.min(plain, Math.max(0, outstanding - refused - keep));
            ledger.consumed(key, refused + plainDrain);
        }
    }

    /** Rejected copies of this exact variant among those held that can be the job's: those above the player's own. */
    private long jobHeld(V variant) {
        if (ledger == null) {
            return 0;
        }
        return Math.max(0, get(refusedHeld, variant) - ledger.preexisting(variant));
    }

    /** Saturates at Integer.MAX_VALUE: a held count that wrapped negative would freeze into a baseline that admits the player's own copies. */
    private static <K> void add(Map<K, Integer> map, K key, int count) {
        Integer current = map.get(key);
        map.put(key, current == null ? count : (int) Math.min((long) current + count, Integer.MAX_VALUE));
    }

    private static <K> int get(Map<K, Integer> map, K key) {
        Integer value = map.get(key);
        return value == null ? 0 : value;
    }
}
