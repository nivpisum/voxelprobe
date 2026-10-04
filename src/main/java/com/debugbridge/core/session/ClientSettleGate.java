package com.debugbridge.core.session;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Defers an action until the client has "settled" — no loading overlay covering
 * the game. Connecting to a server while a resource reload is in flight (the
 * startup reload in particular: the bridge port opens seconds before it
 * finishes) makes the server-resource-pack application — itself a resource
 * reload — collide with the in-flight one ("Reload already ongoing, replacing"
 * in the log), and the pack silently never applies. On servers whose gameplay
 * depends on pack content (custom particle textures, fonts) that wedges the
 * session hard enough that only a process kill recovers it.
 *
 * <p>{@link #runWhenSettled} polls the settled check on the game thread every
 * {@code pollIntervalMs} until it has held for {@code requiredSettledPolls}
 * consecutive polls (margin past the overlay fade-out), then runs the action on
 * the game thread. The calling (request) thread blocks until the action ran, so
 * the bridge response is truthful: success means the connect actually started,
 * and a stuck overlay surfaces as a timeout error instead of a join that
 * silently never happens.
 *
 * <p>Re-scheduling hops through a background scheduler thread instead of
 * re-enqueueing directly from inside the game-thread task: the game thread
 * drains its task queue until empty each frame, so a task that re-submits
 * itself would spin within a single frame and the overlay could never finish.
 *
 * <p>A newer call supersedes a pending one: the older caller gets an error and
 * its action never runs.
 */
public final class ClientSettleGate {

    /** Default bound on how long {@link #runWhenSettled} waits for the client to settle. */
    public static final long DEFAULT_SETTLE_TIMEOUT_MS = 60_000;

    private static final long DEFAULT_POLL_INTERVAL_MS = 150;
    private static final int DEFAULT_REQUIRED_SETTLED_POLLS = 3;

    /** Extra wait past {@code timeoutMs} before concluding the game thread isn't draining tasks at all. */
    private static final long DEFAULT_WAITER_GRACE_MS = 5_000;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DebugBridge-SettleGate");
        t.setDaemon(true);
        return t;
    });

    private final Executor gameThread;
    private final BooleanSupplier settled;
    private final long pollIntervalMs;
    private final int requiredSettledPolls;
    private final long waiterGraceMs;
    private final AtomicReference<CompletableFuture<Void>> active = new AtomicReference<>();

    /**
     * @param gameThread runs tasks on the game thread (e.g. {@code task -> mc.execute(task)})
     * @param settled queried on the game thread; true when no loading overlay is up
     */
    public ClientSettleGate(Executor gameThread, BooleanSupplier settled) {
        this(gameThread, settled, DEFAULT_POLL_INTERVAL_MS, DEFAULT_REQUIRED_SETTLED_POLLS, DEFAULT_WAITER_GRACE_MS);
    }

    ClientSettleGate(
            Executor gameThread,
            BooleanSupplier settled,
            long pollIntervalMs,
            int requiredSettledPolls,
            long waiterGraceMs) {
        this.gameThread = gameThread;
        this.settled = settled;
        this.pollIntervalMs = pollIntervalMs;
        this.requiredSettledPolls = requiredSettledPolls;
        this.waiterGraceMs = waiterGraceMs;
    }

    /**
     * Runs {@code action} on the game thread once the client settles, blocking
     * the calling thread until it ran. Throws when {@code timeoutMs} elapses
     * without the client settling, when a newer call supersedes this one, or
     * when the action itself throws.
     */
    public void runWhenSettled(Runnable action, long timeoutMs) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> prev = active.getAndSet(done);
        if (prev != null) {
            prev.completeExceptionally(new CancellationException("superseded by a newer joinServer request"));
        }
        gameThread.execute(new SettleCheck(done, action, System.nanoTime() + timeoutMs * 1_000_000L, timeoutMs));
        try {
            done.get(timeoutMs + waiterGraceMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The poll loop never observed its own deadline — the game thread
            // isn't draining tasks. Completing the future here also stops a
            // late poll from running the action after we reported failure.
            TimeoutException wedged = new TimeoutException(
                    "game thread unresponsive for " + (timeoutMs + waiterGraceMs) + " ms — client wedged?");
            if (done.completeExceptionally(wedged)) {
                throw wedged;
            }
            // Lost the race: the gate completed while we were giving up — honor the real outcome.
            try {
                done.get(0, TimeUnit.MILLISECONDS);
            } catch (ExecutionException ee) {
                throw unwrap(ee);
            }
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(ExecutionException e) {
        return e.getCause() instanceof Exception cause ? cause : e;
    }

    /** One poll on the game thread; re-schedules itself via {@link #SCHEDULER} until done. */
    private final class SettleCheck implements Runnable {
        private final CompletableFuture<Void> done;
        private final Runnable action;
        private final long deadlineNanos;
        private final long timeoutMs;
        private int consecutiveSettled;

        SettleCheck(CompletableFuture<Void> done, Runnable action, long deadlineNanos, long timeoutMs) {
            this.done = done;
            this.action = action;
            this.deadlineNanos = deadlineNanos;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void run() {
            if (done.isDone()) {
                return; // superseded or timed out — the action must not run anymore
            }
            boolean ok;
            try {
                ok = settled.getAsBoolean();
            } catch (Throwable t) {
                done.completeExceptionally(t);
                return;
            }
            if (ok) {
                if (++consecutiveSettled >= requiredSettledPolls) {
                    try {
                        action.run();
                        done.complete(null);
                    } catch (Throwable t) {
                        done.completeExceptionally(t);
                    }
                    return;
                }
            } else {
                consecutiveSettled = 0;
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                done.completeExceptionally(new TimeoutException(
                        "client did not settle within " + timeoutMs + " ms — startup/reload overlay still active"));
                return;
            }
            SCHEDULER.schedule(() -> gameThread.execute(this), pollIntervalMs, TimeUnit.MILLISECONDS);
        }
    }
}
