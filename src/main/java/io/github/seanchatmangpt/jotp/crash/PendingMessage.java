package io.github.seanchatmangpt.jotp.crash;

import java.time.Duration;
import java.time.Instant;

/**
 * A message that was pending at crash time.
 *
 * <p>Represents a message that was in a process mailbox when the JVM crashed. These messages may
 * need to be replayed during recovery depending on the strategy and message idempotence.
 *
 * @param targetProcessName the name of the target process
 * @param messageClass the class name of the message
 * @param serializedMessage serialized form of the message
 * @param enqueuedAt when the message was enqueued
 * @param sequenceNumber the message sequence number
 * @param isIdempotent whether the message can be safely replayed
 */
public record PendingMessage(
        String targetProcessName,
        String messageClass,
        byte[] serializedMessage,
        Instant enqueuedAt,
        long sequenceNumber,
        boolean isIdempotent) {

    /**
     * Check if this message is stale (older than threshold).
     *
     * <p>Stale messages may be replayed cautiously or discarded depending on business logic.
     *
     * @param threshold the age threshold for staleness
     * @return true if the message is older than the threshold
     * @throws IllegalArgumentException if threshold is null
     */
    public boolean isStale(Duration threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("threshold must not be null");
        }
        return Duration.between(enqueuedAt, Instant.now()).compareTo(threshold) > 0;
    }

    /**
     * Get the age of this message.
     *
     * @return the duration since the message was enqueued
     */
    public Duration age() {
        return Duration.between(enqueuedAt, Instant.now());
    }

    /**
     * Check if this message should be replayed.
     *
     * <p>Messages are replayed if they are idempotent or if they have not been processed yet
     * (sequence number check).
     *
     * @param lastProcessedSeq the last processed sequence number
     * @return true if the message should be replayed
     */
    public boolean shouldReplay(long lastProcessedSeq) {
        return isIdempotent || sequenceNumber > lastProcessedSeq;
    }
}
