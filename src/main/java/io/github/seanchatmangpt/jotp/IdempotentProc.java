package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a {@link Proc} and deduplicates messages by idempotency key.
 *
 * <p>Joe Armstrong: "Design your protocols to survive retries." When a JVM crashes mid-flight and
 * callers retry, duplicate messages carrying the same {@link Idempotent#idempotencyKey()} are
 * silently dropped without invoking the handler. Messages that do not implement {@link Idempotent}
 * are always forwarded to the delegate.
 *
 * <p>The dedup window is a bounded FIFO/LRU cache backed by a {@link LinkedHashMap} with {@code
 * removeEldestEntry} eviction. The cache is wrapped with {@link Collections#synchronizedMap} for
 * thread safety.
 *
 * @param <S> process state type
 * @param <M> message type
 */
public final class IdempotentProc<S, M> {

    /**
     * Marker interface for messages that carry an idempotency key. Messages implementing this
     * interface are deduplicated by {@link IdempotentProc}; duplicate keys within the dedup window
     * are silently discarded.
     */
    public interface Idempotent {
        /**
         * Returns the idempotency key for this message. Two messages with the same key are
         * considered duplicates; the second and subsequent deliveries within the dedup window are
         * dropped.
         *
         * @return non-null idempotency key string
         */
        String idempotencyKey();
    }

    private final Proc<S, M> delegate;
    private final Map<String, Boolean> seenKeys;

    private IdempotentProc(Proc<S, M> delegate, int dedupCacheSize) {
        this.delegate = delegate;
        // Bounded LRU cache: access-order LinkedHashMap evicts eldest when size exceeds
        // dedupCacheSize.
        Map<String, Boolean> lru =
                new LinkedHashMap<>(dedupCacheSize, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > dedupCacheSize;
                    }
                };
        this.seenKeys = Collections.synchronizedMap(lru);
    }

    /**
     * Wrap an existing {@link Proc} with idempotent deduplication.
     *
     * @param proc the underlying process to delegate to
     * @param dedupCacheSize maximum number of idempotency keys retained in the dedup window; once
     *     full, the least-recently-used key is evicted to make room
     * @param <S> process state type
     * @param <M> message type
     * @return a new {@link IdempotentProc} wrapping {@code proc}
     */
    public static <S, M> IdempotentProc<S, M> wrap(Proc<S, M> proc, int dedupCacheSize) {
        if (proc == null) throw new IllegalArgumentException("proc must not be null");
        if (dedupCacheSize <= 0)
            throw new IllegalArgumentException(
                    "dedupCacheSize must be positive, got: " + dedupCacheSize);
        return new IdempotentProc<>(proc, dedupCacheSize);
    }

    /**
     * Send a message to the delegate process.
     *
     * <p>If {@code message} implements {@link Idempotent} and its {@link
     * Idempotent#idempotencyKey()} was already seen within the current dedup window, the message is
     * silently dropped. Otherwise it is forwarded to the delegate via {@link Proc#tell}.
     *
     * @param message the message to deliver
     */
    public void tell(M message) {
        if (message instanceof Idempotent idempotent) {
            String key = idempotent.idempotencyKey();
            // putIfAbsent under the synchronized map lock; returns null iff key was absent (first
            // time)
            if (seenKeys.putIfAbsent(key, Boolean.TRUE) != null) {
                // Duplicate — drop silently
                return;
            }
        }
        delegate.tell(message);
    }

    /**
     * Synchronous request-reply with a bounded timeout.
     *
     * <p>Delegates directly to {@link Proc#ask(Object, Duration)}. Idempotency deduplication is
     * intentionally not applied to ask calls — the caller is waiting for a reply and must handle
     * duplicates at the protocol level if needed.
     *
     * @param message the request message
     * @param timeout maximum time to wait for the process to respond
     * @param <R> expected reply type (the state {@code S} cast to {@code R})
     * @return the process state after the message is handled
     * @throws Exception if the timeout elapses or the process terminates before replying
     */
    @SuppressWarnings("unchecked")
    public <R> R ask(M message, Duration timeout) throws Exception {
        return (R) delegate.ask(message, timeout).get();
    }

    /**
     * Returns the underlying {@link Proc} delegate.
     *
     * <p>Use this to interact with {@link ProcSys}, {@link ProcMonitor}, {@link ProcRegistry}, etc.
     *
     * @return the wrapped process
     */
    public Proc<S, M> delegate() {
        return delegate;
    }
}
