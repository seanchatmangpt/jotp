package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * OTP-style process timers — {@code timer:send_after/3} and {@code timer:send_interval/3}.
 *
 * <p>In OTP, processes model timeouts by receiving timed messages rather than using blocking sleep
 * or callback-based APIs. {@code timer:send_after(Ms, Pid, Msg)} sends {@code Msg} to {@code Pid}
 * after {@code Ms} milliseconds; the process handles it like any other message in its main receive
 * loop.
 *
 * <p>Java 26 mapping:
 *
 * <ul>
 *   <li>OTP {@code timer:send_after(Ms, Pid, Msg)} → {@link #sendAfter(long, Proc, Object)}: one-
 *       shot delayed {@link Proc#tell}; returns a {@link TimerRef} to cancel
 *   <li>OTP {@code timer:send_interval(Ms, Pid, Msg)} → {@link #sendInterval(long, Proc, Object)}:
 *       periodic {@link Proc#tell}; continues until explicitly cancelled
 *   <li>OTP {@code timer:cancel(TRef)} → {@link #cancel(TimerRef)} or {@link TimerRef#cancel()}
 * </ul>
 *
 * <p><strong>Java 26 Design Rationale:</strong> The shared scheduler uses a single daemon platform
 * thread (backing a timer wheel for all timers). This design is optimal because:
 *
 * <ul>
 *   <li><strong>Timer wheels are CPU-bound:</strong> Managing the timer wheel requires precise
 *       scheduling and interrupt handling—work best suited to a single platform thread with
 *       kernel-level timer semantics.
 *   <li><strong>Virtual threads for I/O:</strong> Virtual threads excel at I/O-bound workloads
 *       (network, files); they add overhead for CPU-bound tasks like timer management.
 *   <li><strong>Minimal callback overhead:</strong> The actual callback work is just a non-blocking
 *       {@link Proc#tell(Object)} enqueue into the target process's mailbox, so the timer thread
 *       itself never blocks or yields to application logic.
 *   <li><strong>Fairness:</strong> A single thread ensures all timers are processed with uniform
 *       latency guarantees, avoiding the fairness issues of thread pools.
 * </ul>
 *
 * <p>While Java 26 adds {@code newVirtualThreadPerTaskExecutor()}, using it for timer management
 * would violate the "use virtual threads for I/O, not CPU work" principle and would only increase
 * overhead. The current approach remains the idiomatic choice for OTP-style timers.
 */
public final class ProcTimer {

    private ProcTimer() {}

    /**
     * Shared timer scheduler — single daemon platform thread with internal timer wheel.
     *
     * <p><strong>Java 26 Best Practice:</strong> This implementation intentionally uses a single
     * daemon <em>platform thread</em> rather than virtual threads because timer wheel management is
     * CPU-bound work, not I/O. Virtual threads excel at I/O-bound workloads; using them for pure
     * timer tick counting would add overhead without benefit.
     *
     * <p>The actual callback is a non-blocking {@link Proc#tell(Object)} enqueue, so this thread
     * never blocks on application logic.
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(
                    1,
                    r -> {
                        Thread t = new Thread(r, "proc-timer");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * Opaque handle returned by {@link #sendAfter} and {@link #sendInterval}. Use {@link #cancel}
     * to stop the timer.
     */
    public record TimerRef(ScheduledFuture<?> future) {
        /** Cancel this timer. Returns {@code true} if the timer was still pending. */
        public boolean cancel() {
            return future.cancel(false);
        }
    }

    /**
     * Send {@code msg} to {@code target} after {@code delayMs} milliseconds — one-shot.
     *
     * <p>Mirrors Erlang's {@code timer:send_after(Ms, Pid, Msg)}.
     *
     * @param delayMs delay in milliseconds
     * @param target the process that will receive the message
     * @param msg the message to deliver
     * @return a {@link TimerRef} that can be used to cancel before delivery
     */
    public static <M> TimerRef sendAfter(long delayMs, Proc<?, M> target, M msg) {
        ScheduledFuture<?> future =
                SCHEDULER.schedule(() -> target.tell(msg), delayMs, TimeUnit.MILLISECONDS);
        return new TimerRef(future);
    }

    /**
     * Send {@code msg} to {@code target} every {@code periodMs} milliseconds — repeating.
     *
     * <p>The first delivery occurs after one full period. Mirrors Erlang's {@code
     * timer:send_interval(Ms, Pid, Msg)}.
     *
     * <p>The timer continues indefinitely until {@link #cancel} is called or the JVM exits.
     * Cancelling a stopped {@link Proc}'s interval timer is the caller's responsibility.
     *
     * @param periodMs period in milliseconds
     * @param target the process that will receive the message
     * @param msg the message to deliver on each tick
     * @return a {@link TimerRef} that can be used to stop the interval
     */
    public static <M> TimerRef sendInterval(long periodMs, Proc<?, M> target, M msg) {
        ScheduledFuture<?> future =
                SCHEDULER.scheduleAtFixedRate(
                        () -> target.tell(msg), periodMs, periodMs, TimeUnit.MILLISECONDS);
        return new TimerRef(future);
    }

    /**
     * Duration-based overload of {@link #sendAfter(long, Proc, Object)}.
     *
     * <p>Delegates to the {@code long} millisecond version, keeping the rest of the JOTP API
     * consistent with {@link java.time.Duration}-based timeouts (e.g., {@link Proc#ask(Object,
     * Duration)}).
     *
     * @param delay how long to wait before delivery
     * @param target the process that will receive the message
     * @param msg the message to deliver
     * @return a {@link TimerRef} that can be used to cancel before delivery
     */
    public static <M> TimerRef sendAfter(Duration delay, Proc<?, M> target, M msg) {
        return sendAfter(delay.toMillis(), target, msg);
    }

    /**
     * Duration-based overload of {@link #sendInterval(long, Proc, Object)}.
     *
     * <p>Delegates to the {@code long} millisecond version for consistency with the rest of the
     * JOTP API.
     *
     * @param interval period between deliveries
     * @param target the process that will receive the message on each tick
     * @param msg the message to deliver on each tick
     * @return a {@link TimerRef} that can be used to stop the interval
     */
    public static <M> TimerRef sendInterval(Duration interval, Proc<?, M> target, M msg) {
        return sendInterval(interval.toMillis(), target, msg);
    }

    /**
     * Cancel a timer. Convenience alias for {@link TimerRef#cancel()}.
     *
     * <p>Mirrors Erlang's {@code timer:cancel(TRef)}. Safe to call after the timer has already
     * fired (no-op).
     *
     * @return {@code true} if the timer was still pending and was successfully cancelled
     */
    public static boolean cancel(TimerRef ref) {
        return ref.cancel();
    }
}
