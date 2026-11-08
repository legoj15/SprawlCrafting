package com.legoj15.sprawlcrafting.craft.solver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A map with cheap snapshots: every write is journaled, {@link #mark()} returns a position in the
 * journal and {@link #rollback(int)} undoes the writes after it. The solver tries a branch, then
 * keeps or discards it; copying a whole inventory per try made every resolver visit cost
 * O(inventory size), which a large real inventory turned into tens of milliseconds per solve. With
 * the journal a try costs only the entries it touched.
 *
 * <p>Values are never null (a null value is stored as an absent key). Marks nest: rolling back to an
 * outer mark also undoes everything an inner, already-kept branch wrote.
 */
final class UndoMap<K, V> {
    private final Map<K, V> map;
    /** Alternating key, previous value (null = the key was absent). */
    private final ArrayList<Object> journal = new ArrayList<>();

    UndoMap(Map<K, V> initial) {
        this.map = new HashMap<>(initial);
    }

    V get(K key) {
        return map.get(key);
    }

    void put(K key, V value) {
        if (value == null) {
            remove(key);
            return;
        }
        journal.add(key);
        journal.add(map.put(key, value));
    }

    void remove(K key) {
        if (map.containsKey(key)) {
            journal.add(key);
            journal.add(map.remove(key));
        }
    }

    int mark() {
        return journal.size();
    }

    @SuppressWarnings("unchecked")
    void rollback(int mark) {
        for (int i = journal.size() - 2; i >= mark; i -= 2) {
            K key = (K) journal.get(i);
            V previous = (V) journal.get(i + 1);
            if (previous == null) {
                map.remove(key);
            } else {
                map.put(key, previous);
            }
        }
        journal.subList(mark, journal.size()).clear();
    }

    /** A detached copy of the current contents. */
    Map<K, V> copy() {
        return new HashMap<>(map);
    }
}
