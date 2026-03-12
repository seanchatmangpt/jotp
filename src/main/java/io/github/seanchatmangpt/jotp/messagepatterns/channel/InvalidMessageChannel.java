package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Invalid Message Channel pattern: captures messages that cannot be processed due to validation
 * failures.
 *
 * <p>Enterprise Integration Pattern: <em>Invalid Message Channel</em> (EIP §9.4). Unlike a {@link
 * DeadLetter} (undeliverable), an invalid message was delivered but failed validation.
 *
 * <p>Erlang analog: a dedicated process mailbox collecting malformed messages — similar to {@code
 * error_logger} capturing protocol violations.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka).
 *
 * @param <T> original message type
 */
public final class InvalidMessageChannel<T> {

    /** An invalid message with its validation error. */
    public record InvalidMessage<T>(T message, String validationError, Instant rejectedAt) {
        public static <T> InvalidMessage<T> of(T message, String error) {
            return new InvalidMessage<>(message, error, Instant.now());
        }
    }

    private final ConcurrentLinkedQueue<InvalidMessage<T>> invalid = new ConcurrentLinkedQueue<>();

    /** Record a message as invalid with the given validation error. */
    public void reject(T message, String validationError) {
        invalid.add(InvalidMessage.of(message, validationError));
    }

    /** Drain all invalid messages captured so far. */
    public List<InvalidMessage<T>> drain() {
        return List.copyOf(invalid);
    }

    /** Returns the number of invalid messages captured. */
    public int size() {
        return invalid.size();
    }

    /** Clear all captured invalid messages. */
    public void clear() {
        invalid.clear();
    }
}
