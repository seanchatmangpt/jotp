package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Retry-until-ACK: sends a message and retries on timeout or process crash.
 *
 * <p>Joe Armstrong: "The sender monitors the node, detects DOWN, and resends."
 *
 * <p>In distributed Erlang, messages can be lost when a node crashes mid-flight. The idiomatic OTP
 * solution is for the sender to monitor the target, detect the {@code DOWN} signal, and resend to a
 * restarted process. {@code AckRetry} brings this pattern to Java 26 virtual-thread processes:
 *
 * <ul>
 *   <li>Each attempt calls {@link Proc#ask(Object, Duration)} with a bounded timeout.
 *   <li>On {@link TimeoutException} or process crash ({@link ExecutionException}), the attempt is
 *       retried.
 *   <li>{@link #send} retries immediately; {@link #sendWithBackoff} inserts exponential backoff
 *       between attempts to reduce thundering-herd pressure during recovery.
 * </ul>
 *
 * <p><strong>Idempotency requirement:</strong> Because messages may be delivered more than once
 * (once per attempt before a crash is confirmed), the target handler <em>must</em> be idempotent.
 * Use an idempotency key (e.g. UUID) embedded in the message to deduplicate at the receiver.
 *
 * <p><strong>Usage example:</strong>
 *
 * <pre>{@code
 * Result<Receipt, Exception> result = AckRetry.send(
 *     gatewayProc,
 *     new ChargeMsg(idempotencyKey, amount),
 *     Duration.ofSeconds(5),
 *     3   // max attempts
 * );
 * switch (result) {
 *   case Result.Ok(var receipt) -> log.info("Charged: {}", receipt);
 *   case Result.Err(var ex)     -> log.error("All attempts failed", ex);
 * }
 * }</pre>
 */
public final class AckRetry {

    private AckRetry() {}

    /**
     * Send {@code message} to {@code proc} with retry on timeout or crash.
     *
     * <p>Uses {@link CrashRecovery#retry} internally: each attempt runs in an isolated virtual
     * thread and calls {@link Proc#ask(Object, Duration)}. The first successful reply is returned
     * immediately. If all {@code maxAttempts} are exhausted, the last exception is returned as a
     * {@link Result.Failure}.
     *
     * <p>Retries are immediate (no sleep between attempts). For backoff, use {@link
     * #sendWithBackoff}.
     *
     * @param <S> process state type (returned by {@code ask} after the message is processed)
     * @param <M> message type accepted by the target process
     * @param proc target process to send the message to
     * @param message message to deliver
     * @param timeout per-attempt timeout; if the process does not respond within this duration the
     *     attempt fails with {@link TimeoutException}
     * @param maxAttempts maximum number of send attempts (must be &gt;= 1)
     * @return {@code Result.Ok(state)} on the first successful ACK, or {@code Result.Failure(last
     *     exception)} after all attempts are exhausted
     * @throws IllegalArgumentException if {@code maxAttempts < 1}
     * @throws NullPointerException if any argument is null
     */
    public static <S, M> Result<S, Exception> send(
            Proc<S, M> proc, M message, Duration timeout, int maxAttempts) {
        return CrashRecovery.retry(
                maxAttempts,
                () -> {
                    try {
                        return proc.ask(message, timeout).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted while waiting for ACK", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) throw re;
                        throw new RuntimeException("process error during ask", cause);
                    }
                });
    }

    /**
     * Send {@code message} to {@code proc} with retry and exponential backoff between attempts.
     *
     * <p>Each failed attempt is followed by a delay of {@code initialDelay * 2^attemptIndex} (zero-
     * indexed), doubling on every retry. The backoff is capped at {@code 30 seconds} to prevent
     * unbounded wait times. The delay is computed from {@code initialDelay} as:
     *
     * <pre>
     *   delay(0) = initialDelay
     *   delay(1) = initialDelay * 2
     *   delay(2) = initialDelay * 4
     *   ...
     *   delay(n) = min(initialDelay * 2^n, 30s)
     * </pre>
     *
     * <p>No delay is inserted after the final attempt.
     *
     * @param <S> process state type (returned by {@code ask} after the message is processed)
     * @param <M> message type accepted by the target process
     * @param proc target process to send the message to
     * @param message message to deliver
     * @param perAttemptTimeout per-attempt timeout passed to {@link Proc#ask(Object, Duration)}
     * @param maxAttempts maximum number of send attempts (must be &gt;= 1)
     * @param initialDelay delay inserted after the first failed attempt; doubled on each subsequent
     *     failure
     * @return {@code Result.Ok(state)} on the first successful ACK, or {@code Result.Failure(last
     *     exception)} after all attempts are exhausted
     * @throws IllegalArgumentException if {@code maxAttempts < 1}
     * @throws NullPointerException if any argument is null
     */
    public static <S, M> Result<S, Exception> sendWithBackoff(
            Proc<S, M> proc,
            M message,
            Duration perAttemptTimeout,
            int maxAttempts,
            Duration initialDelay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }

        Duration maxBackoff = Duration.ofSeconds(30);
        Result<S, Exception> last = Result.failure(new IllegalStateException("no attempts made"));

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            last = attemptAsk(proc, message, perAttemptTimeout);
            if (last.isSuccess()) {
                return last;
            }
            // Sleep between failures, but not after the last attempt
            if (attempt < maxAttempts - 1) {
                Duration backoff = backoffDelay(initialDelay, attempt, maxBackoff);
                sleepUninterruptibly(backoff);
            }
        }
        return last;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Executes a single ask attempt in an isolated virtual thread, wrapping the result in a {@link
     * Result}.
     */
    private static <S, M> Result<S, Exception> attemptAsk(
            Proc<S, M> proc, M message, Duration timeout) {
        return CrashRecovery.retry(
                1,
                () -> {
                    try {
                        return proc.ask(message, timeout).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted while waiting for ACK", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) throw re;
                        throw new RuntimeException("process error during ask", cause);
                    }
                });
    }

    /**
     * Computes the backoff delay for a given attempt index.
     *
     * @param initial base delay
     * @param attempt zero-indexed attempt number
     * @param cap maximum delay ceiling
     * @return {@code min(initial * 2^attempt, cap)}
     */
    private static Duration backoffDelay(Duration initial, int attempt, Duration cap) {
        // Use long arithmetic; cap the shift to avoid overflow
        long shiftBits = Math.min(attempt, 62);
        long multiplier = 1L << shiftBits;
        // Saturating multiply: if overflow would occur, clamp to cap
        long initialMs = initial.toMillis();
        long delayMs;
        if (initialMs > 0 && multiplier > Long.MAX_VALUE / initialMs) {
            delayMs = cap.toMillis();
        } else {
            delayMs = Math.min(initialMs * multiplier, cap.toMillis());
        }
        return Duration.ofMillis(delayMs);
    }

    /**
     * Sleeps for the specified duration, restoring the interrupt flag if interrupted rather than
     * propagating the exception (Armstrong: "don't crash the retry loop on OS-level interrupts").
     */
    private static void sleepUninterruptibly(Duration delay) {
        long remaining = delay.toMillis();
        long deadline = System.currentTimeMillis() + remaining;
        while (remaining > 0) {
            try {
                Thread.sleep(remaining);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                remaining = deadline - System.currentTimeMillis();
            }
        }
    }
}
