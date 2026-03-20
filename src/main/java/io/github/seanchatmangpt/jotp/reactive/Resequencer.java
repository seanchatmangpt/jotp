package io.github.seanchatmangpt.jotp.reactive;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Resequencer: restores message order from an out-of-sequence stream.
 *
 * <p>Enterprise Integration Pattern: <em>Resequencer</em> (EIP SS7.8). Messages arrive in arbitrary
 * order. The resequencer buffers them and emits messages in correct sequence once gaps are filled.
 *
 * <p>The sequence key type {@code K} must be comparable and support a "next key" function that
 * computes the expected successor of each key.
 *
 * @param <T> message type
 * @param <K> sequence key type (e.g., Long, Integer, String)
 */
public final class Resequencer<T, K> implements MessageChannel<T>, AutoCloseable {

    private final Function<T, K> keyExtractor;
    private final Function<K, K> nextKeyFn;
    private final MessageChannel<T> downstream;
    private final ConcurrentHashMap<K, T> buffer = new ConcurrentHashMap<>();
    private final LinkedTransferQueue<T> inbound = new LinkedTransferQueue<>();
    private final AtomicReference<K> expectedKey = new AtomicReference<>();
    private final AtomicLong resequenced = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    /**
     * Creates a resequencer that reorders messages based on sequence keys.
     *
     * @param keyExtractor extracts the sequence key from a message
     * @param nextKeyFn computes the next expected key (e.g., {@code n -> n + 1})
     * @param downstream channel to receive ordered messages
     */
    public Resequencer(
            Function<T, K> keyExtractor,
            Function<K, K> nextKeyFn,
            MessageChannel<T> downstream) {
        this.keyExtractor = keyExtractor;
        this.nextKeyFn = nextKeyFn;
        this.downstream = downstream;
        this.worker =
                Thread.ofVirtual()
                        .name("resequencer")
                        .start(this::processLoop);
    }

    private void processLoop() {
        while (running) {
            try {
                T msg = inbound.take();
                K key = keyExtractor.apply(msg);

                // Initialize expected key from the first message if not set
                if (expectedKey.get() == null) {
                    // Find the minimum key — we need to determine the starting point
                    buffer.put(key, msg);
                    tryFlush();
                } else {
                    buffer.put(key, msg);
                    tryFlush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void tryFlush() {
        // If we don't have an expected key, determine the minimum
        if (expectedKey.get() == null) {
            K min = null;
            for (K k : buffer.keySet()) {
                if (min == null || ((Comparable<K>) k).compareTo(min) < 0) {
                    min = k;
                }
            }
            if (min == null) return;
            expectedKey.set(min);
        }

        // Flush consecutive messages starting from expected key
        K current = expectedKey.get();
        while (buffer.containsKey(current)) {
            T msg = buffer.remove(current);
            downstream.send(msg);
            resequenced.incrementAndGet();
            current = nextKeyFn.apply(current);
            expectedKey.set(current);
        }
    }

    @Override
    public void send(T message) {
        inbound.add(message);
    }

    /** Returns the total number of messages emitted in order. */
    public long resequencedCount() {
        return resequenced.get();
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
