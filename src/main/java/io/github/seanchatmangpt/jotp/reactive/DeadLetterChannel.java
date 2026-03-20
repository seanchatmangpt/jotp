package io.github.seanchatmangpt.jotp.reactive;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dead Letter Channel: captures messages that could not be delivered to any consumer.
 *
 * <p>Enterprise Integration Pattern: <em>Dead Letter Channel</em> (EIP SS9.4). Undeliverable
 * messages are stored with metadata for later analysis rather than being silently dropped.
 *
 * @param <T> message type
 */
public final class DeadLetterChannel<T> implements MessageChannel<T> {

    /** A dead letter entry with the original message and arrival timestamp. */
    public record Entry<T>(T message, Instant arrivedAt) {}

    private final ConcurrentLinkedQueue<Entry<T>> deadLetters = new ConcurrentLinkedQueue<>();

    /** Records a message as undeliverable. */
    @Override
    public void send(T message) {
        deadLetters.add(new Entry<>(message, Instant.now()));
    }

    /** Returns the number of dead letters captured. */
    public int size() {
        return deadLetters.size();
    }

    /** Returns an immutable snapshot of all dead letters. */
    public List<Entry<T>> drain() {
        return List.copyOf(deadLetters);
    }

    /** Clears all captured dead letters. */
    public void clear() {
        deadLetters.clear();
    }
}
