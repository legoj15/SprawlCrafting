package com.legoj15.solver;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One craft's bookkeeping against a {@link ProducedLedger}: which identities the pool holds
 * filter-rejected copies of, and which stacks the craft picked, rejected or plain. The executor
 * records the rejected copies the pool holds, opens the craft with {@link #admissible}, records
 * each pick while choosing slots, and {@linkplain #chargeTo charges} the tally once the craft is
 * applied.
 *
 * <p>Rejected copies the pool held before the job produced anything are the player's own, and the
 * ledger keeps that count (the tallies opened before any production record it). Only rejected
 * copies above it can be the job's: a rejected pick is admitted while
 * {@code admissible(ledger).canTake(key, refusedTakes().get(key))}, which caps the job's count at
 * those copies, so a player-owned altered stack is never taken as if crafted and admissions never
 * exceed what the job produced. Altered copies of the player's that only enter the pool after the
 * job's first production (a pickup, an item altered mid-job) cannot be told apart from the job's
 * and stay bounded by the produced count.
 *
 * <p>A plain pick needs no headroom, but it may be the job's own copy, and leaving its count behind
 * is a stale count. So at charge time the count is kept back only for the job's rejected copies the
 * pool still holds after this craft, and plain picks drain anything beyond that. That one rule
 * covers both a plain-only craft while the player's own altered copy is held and a craft that took
 * the rejected copy and plain copies together. A plain pick never spends headroom during the craft,
 * so it cannot block a rejected pick the same craft needs, and the total charged never exceeds the
 * outstanding count.
 */
public final class LedgerTally<K> {

    private final Map<K, Integer> refusedHeld = new HashMap<>();
    private final Map<K, Integer> refusedTakes = new HashMap<>();
    private final Map<K, Integer> refusedTakesView = Collections.unmodifiableMap(refusedTakes);
    private final Map<K, Integer> plainTakes = new HashMap<>();

    /** The pool holds {@code count} more filter-rejected items of {@code key}. Call before {@link #admissible}. */
    public void held(K key, int count) {
        if (count > 0) {
            add(refusedHeld, key, count);
        }
    }

    /**
     * Opens this craft against {@code ledger} and returns the ledger its rejected picks are admitted
     * by: per identity, the job's outstanding count capped at the rejected copies held above the
     * player's own. Until the job first produces something, this also records the rejected copies
     * held as the player's own.
     */
    public ProducedLedger<K> admissible(ProducedLedger<K> ledger) {
        ledger.notePreexisting(refusedHeld);
        ProducedLedger<K> view = new ProducedLedger<>();
        for (Map.Entry<K, Integer> entry : refusedHeld.entrySet()) {
            K key = entry.getKey();
            view.produced(key, Math.min(ledger.outstanding(key), jobHeld(ledger, key, entry.getValue())));
        }
        return view;
    }

    /** One item of {@code key} was picked for this craft; {@code refused}: the filter rejects it. */
    public void picked(K key, boolean refused) {
        picked(key, refused, 1);
    }

    /**
     * {@link #picked} for a batch: records {@code count} picks of {@code key} at once, equivalent to
     * calling {@link #picked(K, boolean)} {@code count} times ({@code count <= 0} is a no-op).
     */
    public void picked(K key, boolean refused, int count) {
        if (count > 0) {
            add(refused ? refusedTakes : plainTakes, key, count);
        }
    }

    /** Read-only live per-identity count of rejected picks so far: the {@code alreadyClaimed} for {@code admissible(ledger).canTake}. */
    public Map<K, Integer> refusedTakes() {
        return refusedTakesView;
    }

    /** Records this craft's picks against {@code ledger}. */
    public void chargeTo(ProducedLedger<K> ledger) {
        ledger.notePreexisting(refusedHeld);
        Set<K> keys = new HashSet<>(refusedTakes.keySet());
        keys.addAll(plainTakes.keySet());
        for (K key : keys) {
            long outstanding = ledger.outstanding(key);
            if (outstanding <= 0) {
                continue;
            }
            long refused = get(refusedTakes, key);
            long plain = get(plainTakes, key);
            // The count stays reserved only for the job's rejected copies still held after this
            // craft; plain picks drain whatever the count left after the rejected picks exceeds that.
            long keep = jobHeld(ledger, key, get(refusedHeld, key) - refused);
            long plainDrain = Math.min(plain, Math.max(0, outstanding - refused - keep));
            ledger.consumed(key, refused + plainDrain);
        }
    }

    /** Rejected copies of {@code key} among {@code held} that can be the job's: those above the player's own. */
    private static <K> long jobHeld(ProducedLedger<K> ledger, K key, long held) {
        return Math.max(0, held - ledger.preexisting(key));
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
