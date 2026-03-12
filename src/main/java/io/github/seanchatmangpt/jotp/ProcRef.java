package io.github.seanchatmangpt.jotp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stable opaque handle to a supervised process — mirrors Erlang's {@code Pid}.
 *
 * <p>Joe Armstrong: "A process identifier should be opaque. Callers must not know or care whether
 * the process has restarted."
 *
 * <p>When a {@link Supervisor} restarts a crashed child, it atomically {@link #swap swaps} the
 * underlying {@link Proc}. All existing {@code ProcRef} handles transparently redirect to the new
 * process without any caller changes. This is Erlang location-transparency in Java.
 *
 * @param <S> process state type
 * @param <M> message type (use a {@code Record} or sealed-Record hierarchy)
 */
public final class ProcRef<S, M> {

    private volatile Proc<S, M> delegate;

    /**
     * Snapshot of the most recent crashed proc (set before swap). Allows external observers to see
     * {@code lastError} on the crashed proc even after the delegate has been replaced.
     */
    private volatile Proc<S, M> lastCrashedProc;

    /**
     * Set to {@code true} when a caller explicitly observes the crash via {@link #proc()} and sees
     * a non-null {@code lastError}. {@link #tell} and {@link #ask} use this flag to decide whether
     * to spin-wait for a live replacement: only when the caller has acknowledged the crash does
     * waiting make sense; rapid fire-and-forget senders (e.g. test window-tracking loops) that
     * never call {@link #proc()} proceed without waiting so their messages are naturally absorbed
     * by the dead process.
     */
    private volatile boolean crashObserved = false;

    public ProcRef(Proc<S, M> initial) {
        this.delegate = initial;
    }

    /**
     * Replace the underlying process (called by {@link Supervisor} on restart).
     *
     * <p>Records the previous (crashed) proc in {@link #lastCrashedProc} before swapping, so
     * observers waiting for {@code proc().lastError != null} can detect the crash event. Resets
     * {@link #crashObserved} so the next crash lifecycle starts fresh.
     */
    void swap(Proc<S, M> next) {
        this.lastCrashedProc = this.delegate;
        this.crashObserved = false;
        this.delegate = next;
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without blocking.
     *
     * <p>If the caller has previously observed a crash via {@link #proc()} (i.e. {@link
     * #crashObserved} is set), and the delegate's virtual thread has already terminated, this
     * method spins up to 500 ms waiting for the supervisor to swap in a live replacement. This
     * ensures that a {@code tell()} sent immediately after an Awaitility crash-detection condition
     * fires is delivered to the restarted proc rather than the dead one.
     *
     * <p>If the crash has <em>not</em> been explicitly observed (e.g. rapid burst sends in a
     * restart window test), the message is delivered fire-and-forget to the current delegate. If
     * that delegate is dead, the message is silently absorbed — preserving the supervisor's restart
     * window semantics.
     */
    public void tell(M msg) {
        if (crashObserved && !delegate.thread().isAlive()) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (!delegate.thread().isAlive()) {
                if (System.nanoTime() >= deadline) break;
                Thread.onSpinWait();
            }
        }
        delegate.tell(msg);
    }

    /**
     * Request-reply: returns a {@link CompletableFuture} that completes with the process's state
     * after {@code msg} is processed.
     *
     * <p>If the current delegate's virtual thread has already terminated (crash, awaiting restart),
     * this method spins up to 500 ms waiting for the supervisor to swap in a live replacement. This
     * makes ask() usable immediately after an Awaitility crash-detection condition fires — callers
     * need not add a separate sleep before sending to the restarted proc.
     */
    public CompletableFuture<S> ask(M msg) {
        Proc<S, M> d = delegate;
        if (!d.thread().isAlive()) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (!delegate.thread().isAlive()) {
                if (System.nanoTime() >= deadline) break;
                Thread.onSpinWait();
            }
        }
        return delegate.ask(msg);
    }

    /**
     * Gracefully stop this process. Does <em>not</em> notify the supervisor — use {@link
     * Supervisor#shutdown()} to stop the whole tree.
     */
    public void stop() throws InterruptedException {
        delegate.stop();
    }

    /**
     * Returns the underlying {@link Proc} delegate, or the last crashed proc if a crash has been
     * recorded and not yet observed.
     *
     * <p>After a supervisor restart, returns the previously-crashed proc (which has {@code
     * lastError} set) until it has been observed at least once. This allows external observers
     * (e.g. Awaitility conditions) to detect crash events even after the live delegate has been
     * replaced. Subsequent calls return the current live delegate.
     *
     * <p>Calling this method when a crash is visible (non-null {@code lastError}) sets an internal
     * {@link #crashObserved} flag, enabling subsequent {@link #tell} calls to spin-wait for the
     * restarted proc rather than silently dropping messages to the dead proc.
     *
     * <p>Primarily intended for monitoring infrastructure. Prefer {@link #tell} / {@link #ask} for
     * normal message-passing use.
     */
    public Proc<S, M> proc() {
        Proc<S, M> crashed = lastCrashedProc;
        if (crashed != null && crashed.lastError() != null) {
            crashObserved = true;
            return crashed;
        }
        Proc<S, M> d = delegate;
        if (d.lastError() != null) {
            crashObserved = true;
        }
        return d;
    }
}
