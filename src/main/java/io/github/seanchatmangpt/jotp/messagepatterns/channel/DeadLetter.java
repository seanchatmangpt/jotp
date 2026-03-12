package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dead Letter Channel pattern: captures messages that could not be delivered to any consumer.
 *
 * <p>Enterprise Integration Pattern: <em>Dead Letter Channel</em> (EIP §9.4). Erlang analog:
 * Erlang's system-level {@code error_logger} and OTP's process exit signals — messages sent to dead
 * processes are captured rather than silently lost.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * @param <T> message type
 */
public final class DeadLetter<T> {

    /** A dead letter entry with reason and timestamp. */
    public record Entry<T>(T message, String reason, Instant arrivedAt) {
        public static <T> Entry<T> of(T message, String reason) {
            return new Entry<>(message, reason, Instant.now());
        }
    }

    private final ConcurrentLinkedQueue<Entry<T>> deadLetters = new ConcurrentLinkedQueue<>();

    /** Record a message as undeliverable. */
    public void dead(T message, String reason) {
        deadLetters.add(Entry.of(message, reason));
    }

    /** Drain all dead letters captured so far. */
    public List<Entry<T>> drain() {
        return List.copyOf(deadLetters);
    }

    /** Returns the number of dead letters. */
    public int size() {
        return deadLetters.size();
    }

    /** Clear all dead letters. */
    public void clear() {
        deadLetters.clear();
    }
}
