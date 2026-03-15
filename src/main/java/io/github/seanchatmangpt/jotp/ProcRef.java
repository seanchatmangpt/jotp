package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

    public ProcRef(Proc<S, M> initial) {
        this.delegate = initial;
    }

    /**
     * Replace the underlying process (called by {@link Supervisor} on restart).
     *
     * <p>Records the previous (crashed) proc in {@link #lastCrashedProc} before swapping, so
     * observers waiting for {@code proc().lastError != null} can detect the crash event.
     */
    void swap(Proc<S, M> next) {
        this.lastCrashedProc = this.delegate;
        this.delegate = next;
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without blocking.
     *
     * <p>If the process is mid-restart, the message goes to the stale process and is lost — callers
     * should use {@link #ask} with Awaitility retries if delivery during restart matters.
     */
    public void tell(M msg) {
        delegate.tell(msg);
    }

    /**
     * Request-reply: returns a {@link CompletableFuture} that completes with the process's state
     * after {@code msg} is processed. Times out naturally if the process is restarting.
     */
    public CompletableFuture<S> ask(M msg) {
        return delegate.ask(msg);
    }

    /**
     * Timed request-reply — mirrors OTP's {@code gen_server:call(Pid, Msg, Timeout)}.
     *
     * @param msg the message to send
     * @param timeout maximum time to wait for a response
     * @return future completing with process state, or timing out with {@link
     *     java.util.concurrent.TimeoutException}
     */
    public CompletableFuture<S> ask(M msg, Duration timeout) {
        return delegate.ask(msg, timeout);
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
     * <p>Primarily intended for monitoring infrastructure. Prefer {@link #tell} / {@link #ask} for
     * normal message-passing use.
     */
    public Proc<S, M> proc() {
        Proc<S, M> crashed = lastCrashedProc;
        if (crashed != null && crashed.lastError() != null) {
            return crashed;
        }
        return delegate;
    }
}
