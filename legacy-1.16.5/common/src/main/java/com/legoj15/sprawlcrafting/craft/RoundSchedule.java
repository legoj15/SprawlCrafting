package com.legoj15.sprawlcrafting.craft;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * The round-by-round walk of an interleaved job's steps: a plan whose steps hold more crafts than one
 * round should hold (a single step can carry 1e15) is executed in {@link #rounds()} rounds, each round
 * performing a proportional slice of every step in plan order, so an intermediate is never held much
 * beyond one round's output instead of the whole step's. Step i's target in round r is the cumulative
 * {@code floor(crafts[i] * r / N)} (exact at {@code r == N}); after round N unfinished steps are retried
 * in drain passes over their full targets. Kept free of Minecraft types so the rule is unit-testable.
 *
 * <p>A cursor walks the steps in plan order within a round: the current step is the next index whose
 * done count is below its target and that was not deferred this round ({@link #defer()}); when the
 * cursor passes the last step the round advances. A drain pass in which no craft was performed means
 * the job is stuck ({@link #defer()} then returns false); a round that stalls is not stuck, because a
 * later round's grown targets can still repay the shortage.
 */
final class RoundSchedule {

    private final long[] crafts;
    private final long[] done;
    private final long roundCrafts;
    private final long rounds;
    /** Steps still below their total; 0 means the schedule (and so the job) is finished. */
    private int remaining;
    /** The current round, 1..{@link #rounds()}; past that, drain passes counted {@code rounds + 1, ...}. */
    private long round = 1;
    private int cursor;
    private final boolean[] deferred;
    /** Whether any craft was performed in the pass now running (a stuck drain pass is one without). */
    private boolean progressedThisPass;
    private boolean stuck;
    /** Crafts performed across every step so far, so {@link #totalDone()} is O(1). */
    private long totalDone;

    /**
     * @param crafts     each step's total crafts, in plan order (every entry >= 1)
     * @param roundCrafts the round size: per round each step makes at most about this many crafts (>= 1)
     */
    RoundSchedule(long[] crafts, long roundCrafts) {
        if (crafts == null || crafts.length == 0) {
            throw new IllegalArgumentException("a schedule needs at least one step");
        }
        if (roundCrafts < 1) {
            throw new IllegalArgumentException("roundCrafts must be >= 1, got " + roundCrafts);
        }
        this.crafts = crafts.clone();
        long max = 0;
        for (long total : this.crafts) {
            if (total < 1) {
                throw new IllegalArgumentException("crafts must be >= 1, got " + total);
            }
            max = Math.max(max, total);
        }
        this.done = new long[crafts.length];
        this.roundCrafts = roundCrafts;
        this.rounds = max / roundCrafts + (max % roundCrafts != 0 ? 1 : 0);
        this.remaining = crafts.length;
        this.deferred = new boolean[crafts.length];
    }

    /** Whether some step's total exceeds the round size, so the plan must run interleaved. */
    boolean interleaved() {
        for (long total : crafts) {
            if (total > roundCrafts) {
                return true;
            }
        }
        return false;
    }

    /** The step the cursor is on, or -1 once every step has reached its total. */
    int current() {
        while (true) {
            if (finished()) {
                return -1;
            }
            if (done[cursor] < target(cursor) && !deferred[cursor]) {
                return cursor;
            }
            if (cursor < crafts.length - 1) {
                cursor++;
                continue;
            }
            advancePass();
        }
    }

    /** The current step's remaining crafts for this round (its cumulative target less its done count). */
    long leftInRound() {
        int step = current();
        return step < 0 ? 0 : target(step) - done[step];
    }

    /**
     * Records {@code n} crafts of the current step (1..{@link #leftInRound()}).
     *
     * @return true when those crafts finished the step's slice for this round, i.e. the cursor moved on
     */
    boolean performed(long n) {
        int step = current();
        if (step < 0) {
            throw new IllegalStateException("the schedule is finished");
        }
        long left = target(step) - done[step];
        if (n < 1 || n > left) {
            throw new IllegalArgumentException("crafts performed must be in [1, " + left + "], got " + n);
        }
        done[step] += n;
        progressedThisPass = true;
        totalDone += n;
        if (done[step] == crafts[step]) {
            remaining--;
        }
        return done[step] >= target(step);
    }

    /**
     * The current step cannot run now (its ingredients are not there yet): skip it for the rest of this
     * round or drain pass.
     *
     * @return false only when the job is stuck — a whole drain pass applied no crafts, so no later
     *         pass can either (its targets are already the full totals)
     */
    boolean defer() {
        if (stuck) {
            return false;
        }
        int step = current();
        if (step < 0) {
            return true;
        }
        deferred[step] = true;
        for (int i = step; i < crafts.length; i++) {
            if (done[i] < target(i) && !deferred[i]) {
                return true; // this pass still has a runnable step
            }
        }
        long endedRound = round; // full targets there (r == N) or in a drain pass: no progress is final
        boolean progressed = progressedThisPass; // read before advancePass resets it
        if (!progressed && round < rounds) {
            // Every attempted step was short and none made a craft. Rounds in which no step's target
            // grows past what it has done would only ask the same steps again, so skip to the first
            // round in which a step that sat at its target here gets more to do (it may supply the
            // short ones), or to the full targets when none does.
            round = nextGrowingRound() - 1;
        }
        advancePass();
        if (endedRound >= rounds && !progressed) {
            stuck = true;
            return false;
        }
        return true;
    }

    boolean finished() {
        return remaining == 0;
    }

    /** Step {@code i}'s crafts performed so far. */
    long done(int i) {
        return done[i];
    }

    /** Crafts performed across every step so far. */
    long totalDone() {
        return totalDone;
    }

    /** The round now running: 1..{@link #rounds()}, then drain passes past it. */
    long round() {
        return round;
    }

    long rounds() {
        return rounds;
    }

    /** Step {@code i}'s cumulative target in the round now running. */
    private long target(int i) {
        return round >= rounds ? crafts[i] : cumulativeTarget(crafts[i], round, rounds);
    }

    /** Ends the round or drain pass now running: deferred flags and the cursor start over. */
    /**
     * The first round after this one in which an unfinished step that is at its target now gets a
     * larger target ({@code floor(crafts * r / N) > done}, i.e. {@code r >= ceil((done + 1) * N / crafts)}),
     * or {@link #rounds} when there is none.
     */
    private long nextGrowingRound() {
        long next = rounds;
        BigInteger n = BigInteger.valueOf(rounds);
        for (int i = 0; i < crafts.length; i++) {
            if (done[i] >= crafts[i] || done[i] < target(i)) {
                continue;
            }
            BigInteger need = BigInteger.valueOf(done[i] + 1).multiply(n);
            BigInteger[] qr = need.divideAndRemainder(BigInteger.valueOf(crafts[i]));
            BigInteger r = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
            if (r.compareTo(BigInteger.valueOf(next)) < 0) {
                next = Math.max(round + 1, r.longValue());
            }
        }
        return next;
    }

    private void advancePass() {
        round++;
        cursor = 0;
        progressedThisPass = false;
        Arrays.fill(deferred, false);
    }

    /**
     * {@code floor(crafts * r / N)} exactly and without overflow: {@code q * r + rem * r / N} with
     * {@code q = crafts / N} and {@code rem = crafts % N} ({@code q * r <= crafts}, but
     * {@code rem * r} can exceed a long, and then {@link BigInteger} computes that term). Used with
     * {@code r == N} too, where it returns {@code crafts} exactly.
     */
    static long cumulativeTarget(long crafts, long r, long N) {
        long q = crafts / N;
        long rem = crafts % N;
        long low = q * r;
        if (rem == 0 || r <= Long.MAX_VALUE / rem) {
            return low + rem * r / N;
        }
        return BigInteger.valueOf(rem).multiply(BigInteger.valueOf(r))
                .divide(BigInteger.valueOf(N)).longValueExact() + low;
    }
}
