package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.Result;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Idempotent Receiver — deduplicates messages by ID using a bounded LRU history.
 *
 * <p>Implements the Idempotent Receiver EIP pattern (Hohpe &amp; Woolf, ch. 5). When the same
 * message ID is received more than once, subsequent deliveries are suppressed and the handler is
 * not invoked again.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var receiver = IdempotentReceiver.<Order>create(1000);
 *
 * var result = receiver.receive(messageId, order, (state, msg) -> processOrder(msg));
 * result.fold(
 *     r -> r.isDuplicate() ? "dup" : "processed: " + r.result(),
 *     err -> "error: " + err
 * );
 * }</pre>
 *
 * @param <M> the message type
 */
public final class IdempotentReceiver<M> {

    private final int maxHistory;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong seen = new AtomicLong(0);
    private final AtomicLong processed = new AtomicLong(0);
    private final AtomicLong duplicates = new AtomicLong(0);

    // LRU cache: insertion-order map, eldest removed when size exceeds maxHistory
    private final Map<UUID, Boolean> seenIds;

    private volatile boolean alive = true;

    private IdempotentReceiver(int maxHistory) {
        if (maxHistory <= 0) {
            throw new IllegalArgumentException("maxHistory must be positive, got: " + maxHistory);
        }
        this.maxHistory = maxHistory;
        this.seenIds =
                new LinkedHashMap<>(maxHistory, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                        return size() > maxHistory;
                    }
                };
    }

    /**
     * Creates a new IdempotentReceiver with the given LRU history size.
     *
     * @param maxHistory maximum number of message IDs to track (eldest evicted when exceeded)
     * @param <M> the message type
     * @return a new receiver instance
     */
    public static <M> IdempotentReceiver<M> create(int maxHistory) {
        return new IdempotentReceiver<>(maxHistory);
    }

    /**
     * Receives a message, invoking the handler only if the message ID has not been seen before.
     *
     * @param messageId unique identifier for this message
     * @param message the message payload (must not be null)
     * @param handler the processing function; receives current state and message, returns result
     * @param <R> the handler result type
     * @return {@code Result.ok(ProcessingResult)} on success, {@code Result.err(reason)} on handler
     *     exception
     * @throws NullPointerException if any argument is null
     */
    public <R> Result<ProcessingResult<R>, String> receive(
            UUID messageId, M message, BiFunction<IdempotentReceiver<M>, M, R> handler) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        lock.lock();
        try {
            seen.incrementAndGet();
            if (seenIds.containsKey(messageId)) {
                duplicates.incrementAndGet();
                return Result.ok(new ProcessingResult<>(true, null));
            }
            seenIds.put(messageId, Boolean.TRUE);
        } finally {
            lock.unlock();
        }

        // Handler invoked outside the lock to avoid holding it during potentially slow processing
        try {
            R result = handler.apply(this, message);
            processed.incrementAndGet();
            return Result.ok(new ProcessingResult<>(false, result));
        } catch (Exception ex) {
            // Remove ID so the message could be re-tried (matches test expectation of failure)
            lock.lock();
            try {
                seenIds.remove(messageId);
            } finally {
                lock.unlock();
            }
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            return Result.err(reason);
        }
    }

    /**
     * Returns {@code true} if the given message ID has already been processed.
     *
     * @param messageId the message ID to check
     * @return {@code true} if seen before
     */
    public boolean isDuplicate(UUID messageId) {
        lock.lock();
        try {
            return seenIds.containsKey(messageId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of unique message IDs currently tracked (bounded by {@code maxHistory}).
     *
     * @return tracked count
     */
    public int trackedCount() {
        lock.lock();
        try {
            return seenIds.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a summary statistics string wrapped in a {@link Result}.
     *
     * @return {@code Result.ok(stats)} where stats includes seen/processed/duplicates counts
     */
    public Result<String, String> statistics() {
        return Result.ok(
                "seen=" + seen.get()
                        + ", processed="
                        + processed.get()
                        + ", duplicates="
                        + duplicates.get()
                        + ", tracked="
                        + trackedCount());
    }

    /**
     * Clears all tracked message IDs and resets statistics.
     */
    public void reset() {
        lock.lock();
        try {
            seenIds.clear();
            seen.set(0);
            processed.set(0);
            duplicates.set(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this receiver is active (not stopped).
     *
     * @return {@code true} if alive
     */
    public boolean isAlive() {
        return alive;
    }

    /**
     * Stops this receiver, releasing resources.
     *
     * @throws InterruptedException if interrupted while stopping
     */
    public void stop() throws InterruptedException {
        alive = false;
    }

    /**
     * Result of a receive operation.
     *
     * @param isDuplicate {@code true} if this message was a duplicate and the handler was skipped
     * @param result the handler's return value; {@code null} for duplicates
     * @param <R> the result type
     */
    public record ProcessingResult<R>(boolean isDuplicate, R result) {}
}
