package io.github.seanchatmangpt.jotp;

import java.util.UUID;

/**
 * An idempotency key implementing Joe Armstrong's "idempotent operations + ACK retry" pattern.
 *
 * <p>Joe Armstrong's philosophy: in a distributed system, the only safe way to handle network
 * partitions and JVM crashes is to make every operation idempotent. When a caller sends a message
 * and the JVM crashes before receiving the ACK, the caller cannot know whether the operation
 * succeeded. The solution: attach an idempotency key to every operation, and retry freely. The
 * receiver deduplicates by key, ensuring exactly-once semantics despite at-least-once delivery.
 *
 * <p>Usage pattern:
 *
 * <pre>{@code
 * // Sender: generate once, retry with same key
 * IdempotencyKey key = IdempotencyKey.generate();
 * // ... send operation with key, crash, retry with same key
 *
 * // Receiver: deduplicate
 * if (seen.add(key)) {
 *   performOperation();
 *   ack(key);
 * }
 * }</pre>
 *
 * @param value the unique string identifier for this idempotency key
 */
public record IdempotencyKey(String value) {

    /** Compact constructor that validates the value is non-null and non-blank. */
    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "IdempotencyKey value must be non-null and non-blank");
        }
    }

    /**
     * Creates a new random UUID-based idempotency key.
     *
     * <p>Each call generates a cryptographically random UUID, suitable for use as a unique
     * operation identifier across distributed systems and JVM restarts.
     *
     * @return a new {@code IdempotencyKey} with a random UUID value
     */
    public static IdempotencyKey generate() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }

    /**
     * Wraps an existing key string as an {@code IdempotencyKey}.
     *
     * <p>Use this when reconstructing a key received from a caller (e.g., from an HTTP header or
     * message envelope) to participate in deduplication.
     *
     * @param value the existing key string; must be non-null and non-blank
     * @return an {@code IdempotencyKey} wrapping the given value
     * @throws IllegalArgumentException if {@code value} is null or blank
     */
    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
