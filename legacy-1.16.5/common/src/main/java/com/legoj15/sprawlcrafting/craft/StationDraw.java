package com.legoj15.sprawlcrafting.craft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ToIntBiFunction;

/**
 * The all-or-nothing draw of one craft's share from a crafting station's storage ({@link StationStorage}),
 * kept free of Minecraft types so the rule that keeps items from being duplicated or voided is unit-testable.
 * Java 8 port of the root tree's class of the same name; behaviour identical.
 *
 * @param <K> what is drawn (an {@code Item} in the game)
 */
final class StationDraw {

    private StationDraw() {
    }

    /**
     * Extracts every entry of {@code takes} through {@code extract} (which returns how many it really
     * removed, 0..requested). Returns null when every entry came out in full.
     *
     * <p>Whenever the draw does not complete, everything this call already extracted goes back through
     * {@code giveBack} before the call returns or throws, so nothing extracted is held by nobody:
     * <ul>
     *   <li>an entry that comes up short: all extracted so far (the short entry's partial amount
     *       included) is given back and that entry's key is returned;</li>
     *   <li>{@code extract} throwing (the storage's mod failing mid-loop): the same give-back runs, then
     *       the exception is rethrown.</li>
     * </ul>
     * A failure inside {@code giveBack} does not stop the remaining entries being given back; the first
     * one is thrown afterwards (or, when {@code extract} already threw, attached to that exception as
     * suppressed). Each extracted amount is given back exactly once.
     */
    static <K> K draw(Map<K, Integer> takes, ToIntBiFunction<K, Integer> extract, BiConsumer<K, Integer> giveBack) {
        Map<K, Integer> drawn = new LinkedHashMap<>();
        K shortKey = null;
        try {
            for (Map.Entry<K, Integer> take : takes.entrySet()) {
                int got = extract.applyAsInt(take.getKey(), take.getValue());
                if (got > 0) {
                    drawn.merge(take.getKey(), got, Integer::sum);
                }
                if (got < take.getValue()) {
                    shortKey = take.getKey();
                    break;
                }
            }
        } catch (Throwable failure) {
            // Whatever the storage's mod threw (even an Error): the items drawn before it are real and
            // held by nobody, so return them before the failure propagates.
            giveAllBack(drawn, giveBack, failure);
            throw failure;
        }
        if (shortKey != null) {
            Throwable giveBackFailure = giveAllBack(drawn, giveBack, null);
            if (giveBackFailure instanceof RuntimeException) {
                throw (RuntimeException) giveBackFailure;
            } else if (giveBackFailure instanceof Error) {
                throw (Error) giveBackFailure;
            }
        }
        return shortKey;
    }

    /**
     * Gives every entry of {@code drawn} back, whatever one give-back does. A failure is attached to
     * {@code cause} as suppressed when there is one; otherwise the first is returned (later ones attached
     * to it) for the caller to throw.
     */
    private static <K> Throwable giveAllBack(Map<K, Integer> drawn, BiConsumer<K, Integer> giveBack,
                                             Throwable cause) {
        Throwable first = null;
        for (Map.Entry<K, Integer> entry : drawn.entrySet()) {
            try {
                giveBack.accept(entry.getKey(), entry.getValue());
            } catch (Throwable failure) {
                if (cause != null) {
                    cause.addSuppressed(failure);
                } else if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        return first;
    }
}
