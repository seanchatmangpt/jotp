package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records messages sent to a process with sequence numbers and nanosecond timestamps.
 *
 * <p>Wraps a {@link Proc} and intercepts every {@code tell} and {@code ask}, logging each message
 * as an {@link Entry} before forwarding it to the underlying process. This creates a durable,
 * ordered record of all messages suitable for deterministic replay via {@link MessageReplayer}.
 *
 * <p>Erlang equivalent: a tracing process that intercepts messages before delivery, analogous to
 * {@code dbg:tracer/0} or a custom proxy process.
 *
 * <p><strong>Thread Safety:</strong> The internal log uses a {@link ConcurrentLinkedQueue} and an
 * {@link AtomicLong} sequence counter, making concurrent {@code tell}/{@code ask} calls safe.
 *
 * @param <M> message type accepted by the wrapped process
 */
public final class MessageRecorder<M> {

    /**
     * A recorded message entry — immutable snapshot of one intercepted message.
     *
     * @param seqNum monotonically increasing sequence number (1-based)
     * @param message the original message value
     * @param nanoTime {@link System#nanoTime()} at interception
     * @param <M> message type
     */
    public record Entry<M>(long seqNum, M message, long nanoTime) {}

    private final Proc<?, M> target;
    private final ConcurrentLinkedQueue<Entry<M>> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong seq = new AtomicLong(0);

    private MessageRecorder(Proc<?, M> target) {
        this.target = target;
    }

    /**
     * Wrap an existing {@link Proc}, returning a recorder that forwards all messages while logging
     * them.
     *
     * @param proc the process to wrap
     * @param <S> state type
     * @param <M> message type
     * @return a new {@link MessageRecorder} wrapping {@code proc}
     */
    public static <S, M> MessageRecorder<M> wrap(Proc<S, M> proc) {
        return new MessageRecorder<>(proc);
    }

    /**
     * Fire-and-forget: log the message and forward it to the wrapped process.
     *
     * @param msg the message to send
     */
    public void tell(M msg) {
        record(msg);
        target.tell(msg);
    }

    /**
     * Request-reply: log the message and forward it to the wrapped process.
     *
     * <p>The returned future completes with the process state after the message is handled.
     *
     * @param msg the message to send
     * @param <S> state type (inferred from the wrapped proc's return type)
     * @return future that completes with the new state
     */
    @SuppressWarnings("unchecked")
    public <S> CompletableFuture<S> ask(M msg) {
        record(msg);
        return ((Proc<S, M>) target).ask(msg);
    }

    /**
     * Timed request-reply: log the message and forward with a timeout.
     *
     * @param msg the message to send
     * @param timeout maximum wait for a response
     * @param <S> state type
     * @return future that completes with the new state or exceptionally on timeout
     */
    @SuppressWarnings("unchecked")
    public <S> CompletableFuture<S> ask(M msg, Duration timeout) {
        record(msg);
        return ((Proc<S, M>) target).ask(msg, timeout);
    }

    /**
     * Returns a live view of the log (backed by the internal queue). Callers should prefer {@link
     * #snapshot()} when they need a stable, immutable copy for replay.
     *
     * @return unmodifiable live list of all recorded entries in insertion order
     */
    public List<Entry<M>> log() {
        return Collections.unmodifiableList(new ArrayList<>(queue));
    }

    /**
     * Returns an immutable snapshot of the current log, suitable for passing to a {@link
     * MessageReplayer}.
     *
     * @return immutable list of all entries recorded so far
     */
    public List<Entry<M>> snapshot() {
        return List.copyOf(new ArrayList<>(queue));
    }

    /**
     * Clears the recorded log without affecting the underlying process state or its mailbox.
     *
     * <p>Sequence numbers will restart from 1 after clearing.
     */
    public void clear() {
        queue.clear();
        seq.set(0);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void record(M msg) {
        long sn = seq.incrementAndGet();
        queue.add(new Entry<>(sn, msg, System.nanoTime()));
    }
}
