package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Replays a recorded message sequence against a fresh process for deterministic test execution.
 *
 * <p>Each message in the log is sent via {@link Proc#ask} so the replayer waits for the process to
 * finish handling one message before sending the next. This guarantees the same total ordering as
 * the original recording regardless of how many virtual threads are involved.
 *
 * <p>Erlang equivalent: a test harness that drives a {@code gen_server} through a pre-recorded
 * sequence of calls, checking the state after each one.
 *
 * <p><strong>Error handling:</strong> If a message causes the process to throw, the exception is
 * captured as a {@link ReplayError} and replay continues with the next message. The final {@link
 * Result} records all errors encountered.
 *
 * @param <S> process state type
 * @param <M> message type
 */
public final class MessageReplayer<S, M> {

    /** Default per-message ask timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * A single replay error — the message that caused the failure and the exception it produced.
     *
     * @param seqNum sequence number of the offending entry
     * @param message the message that triggered the error
     * @param cause the exception thrown by the process handler
     */
    public record ReplayError(long seqNum, Object message, Throwable cause) {}

    /**
     * The outcome of a replay run.
     *
     * @param finalState process state after the last successfully-processed message, or the initial
     *     state if every message failed
     * @param messagesReplayed number of messages sent (including those that produced errors)
     * @param errors list of errors encountered during replay (empty means clean run)
     * @param <S> state type
     */
    public record Result<S>(S finalState, int messagesReplayed, List<ReplayError> errors) {
        /**
         * Returns {@code true} if no errors occurred during replay.
         *
         * @return {@code true} when {@link #errors()} is empty
         */
        public boolean succeeded() {
            return errors.isEmpty();
        }
    }

    private final Proc<S, M> proc;

    private MessageReplayer(Proc<S, M> proc) {
        this.proc = proc;
    }

    /**
     * Create a replayer targeting the given process.
     *
     * @param proc the process that will receive the replayed messages
     * @param <S> state type
     * @param <M> message type
     * @return a new {@link MessageReplayer} bound to {@code proc}
     */
    public static <S, M> MessageReplayer<S, M> create(Proc<S, M> proc) {
        return new MessageReplayer<>(proc);
    }

    /**
     * Replay {@code log} with no inter-message delay ({@code timeScale = 0.0}).
     *
     * <p>Messages are sent as fast as the process can handle them. Each {@link Proc#ask} call
     * blocks until the process has processed the message (up to a 5 s timeout per message).
     *
     * @param log the ordered list of entries to replay
     * @return the replay result
     */
    public Result<S> replay(List<MessageRecorder.Entry<M>> log) {
        return replay(log, 0.0);
    }

    /**
     * Replay {@code log} with optional timing control.
     *
     * <p>The {@code timeScale} parameter controls inter-message delays based on the original
     * recording timestamps:
     *
     * <ul>
     *   <li>{@code 0.0} — no delays (as fast as possible, default for unit tests)
     *   <li>{@code 1.0} — real-time delays matching original inter-message gaps
     *   <li>{@code 0.5} — half-speed (delays halved)
     * </ul>
     *
     * <p>Each message is sent synchronously via {@link Proc#ask} with a 5-second timeout. If the
     * process handler throws for a given message, the error is captured and replay continues with
     * the next entry.
     *
     * @param log the ordered list of entries to replay
     * @param timeScale delay scaling factor (0.0 = no delay, 1.0 = real-time)
     * @return the replay result
     */
    public Result<S> replay(List<MessageRecorder.Entry<M>> log, double timeScale) {
        if (log.isEmpty()) {
            // Return an initial-state snapshot by sending a dummy ask would be wrong;
            // instead we can only report 0 messages and no state.  We obtain the state
            // by asking ProcSys if it's available, but to keep it simple and avoid extra
            // dependencies we record null as the finalState for an empty log.
            return new Result<>(null, 0, List.of());
        }

        List<ReplayError> errors = new ArrayList<>();
        S lastGoodState = null;
        long prevNano = log.getFirst().nanoTime();

        for (MessageRecorder.Entry<M> entry : log) {
            // Apply inter-message delay if requested
            if (timeScale > 0.0 && prevNano != entry.nanoTime()) {
                long gapNanos = entry.nanoTime() - prevNano;
                long sleepNanos = (long) (gapNanos * timeScale);
                if (sleepNanos > 0) {
                    try {
                        Thread.sleep(Duration.ofNanos(sleepNanos));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        // Stop replay gracefully on interrupt
                        break;
                    }
                }
            }
            prevNano = entry.nanoTime();

            try {
                // Use timed get() so TimeoutException is checked at compile time
                CompletableFuture<S> future = proc.ask(entry.message());
                S state = future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                lastGoodState = state;
            } catch (ExecutionException ee) {
                errors.add(new ReplayError(entry.seqNum(), entry.message(), ee.getCause()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                errors.add(new ReplayError(entry.seqNum(), entry.message(), ie));
                break;
            } catch (TimeoutException te) {
                errors.add(new ReplayError(entry.seqNum(), entry.message(), te));
            }
        }

        return new Result<>(lastGoodState, log.size(), List.copyOf(errors));
    }
}
