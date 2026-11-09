package com.legoj15.sprawlcrafting.craft;

import java.util.function.IntSupplier;

/**
 * The wall-time budget that every player's batched-craft burst shares within one server tick, so N
 * players bursting together cost about {@link CraftJob#SERVER_TICK_BURST_BUDGET_NANOS} in total instead
 * of N times {@link CraftJob#BURST_BUDGET_NANOS}.
 *
 * <p>Fairness comes from the slice, not from who runs first. The tick's budget is divided evenly among
 * the jobs about to burst when the tick's first burst asks (capped at the per-job limit, so a lone
 * player is unchanged), and every burst is granted that same slice whatever its position in the
 * player loop. A first-come-first-served pool would let the first few players in the loop eat the whole
 * budget every tick and leave the rest at the one-craft floor forever; the loop order is fixed by the
 * player list and the loaders' tick hooks, so it cannot simply be rotated.
 *
 * <p>A burst is charged the time it used <em>up to its grant</em>, never its overshoot. The burst loop
 * checks the clock after each craft, so every burst runs up to one craft past its grant; charging that
 * overshoot to the shared window would shrink the slice of every player later in the fixed loop order
 * (the last one pinned to the one-craft floor every tick). A burst that ends early (64-craft cap, step
 * boundary) is charged only what it used, and the rest stays in the window for any burst the job count
 * missed (one that starts later in the same tick). The time charged to the window never exceeds the
 * budget; grants are sized from the job count, so their sum stays within it only while that count matches
 * the bursts that actually run.
 *
 * <p>No starvation: this class only says how long a burst <em>may</em> run. The burst loop in
 * {@code CraftQueueManager} checks the clock after a craft, never before, so a zero grant (budget
 * already spent) still lets a job do its one craft. The shared budget is therefore a soft bound: the
 * total is the budget plus at most one craft's overshoot per burst, and beyond roughly
 * {@code budget / cost-of-one-craft} concurrent bulk jobs the one-craft floor dominates by design.
 *
 * <p>A window is keyed on the <b>server tick</b> ({@code MinecraftServer.getTickCount()}), never on
 * {@code player.tickCount}: that counter is per-entity and restarts at 0 on respawn, which would wedge a
 * window keyed on it. Any change of key opens a fresh window, so a clock that moves backwards (new
 * server, wrapped counter) refills the budget instead of stalling it.
 *
 * <p>Not thread-safe: used from the server thread only, like the rest of the queue manager. Pure Java
 * with no Minecraft types, so the arithmetic is unit-testable.
 */
public final class BurstBudget {

    private final long tickBudgetNanos;
    private final long jobCapNanos;

    private boolean windowOpen;
    private long windowTick;
    private long remainingNanos;
    private long sliceNanos;
    private long lastGrantNanos;

    /**
     * @param tickBudgetNanos total burst time all players may spend in one server tick
     * @param jobCapNanos     most any single burst may spend (the lone-player limit)
     */
    public BurstBudget(long tickBudgetNanos, long jobCapNanos) {
        this.tickBudgetNanos = tickBudgetNanos;
        this.jobCapNanos = jobCapNanos;
    }

    /**
     * How long the burst starting now may run. Opens the window for {@code serverTick} on the first
     * call of that tick (asking {@code burstingJobs} once for the number of jobs sharing it). Pair every
     * grant with one {@link #charge} once the burst is done.
     *
     * @return nanoseconds, never negative; 0 when the tick's budget is already spent, in which case the
     *         caller still performs its first craft
     */
    public long grant(long serverTick, IntSupplier burstingJobs) {
        if (!windowOpen || serverTick != windowTick) {
            windowOpen = true;
            windowTick = serverTick;
            remainingNanos = tickBudgetNanos;
            sliceNanos = Math.min(jobCapNanos, tickBudgetNanos / Math.max(1, burstingJobs.getAsInt()));
        }
        lastGrantNanos = Math.max(0L, Math.min(sliceNanos, remainingNanos));
        return lastGrantNanos;
    }

    /**
     * Takes the time the burst just granted used out of the open window, clamped to its grant: the
     * overshoot past the grant (at most one craft) is never charged, so it cannot shrink the bursts after
     * it. Call once per {@link #grant}; a second call charges nothing.
     */
    public void charge(long usedNanos) {
        remainingNanos -= Math.min(Math.max(0L, usedNanos), lastGrantNanos);
        lastGrantNanos = 0L;
    }

    /** Forgets the open window; the next grant opens a fresh one (server shutdown). */
    public void reset() {
        windowOpen = false;
        lastGrantNanos = 0L;
    }
}
