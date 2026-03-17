package io.github.seanchatmangpt.jotp.messaging.system;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Message Expiration — TTL-based filtering for time-sensitive messages.
 *
 * <p>Implements the Message Expiration EIP pattern (Hohpe &amp; Woolf, ch. 8). Messages older than
 * their declared TTL are silently discarded rather than processed with stale data.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var expiration = MessageExpiration.create(10_000); // 10-second background cleanup
 *
 * var msg = expiration.withExpiration(myMessage, 5_000); // 5-second TTL
 *
 * // Later — only process messages that haven't expired
 * var live = expiration.retainNonExpired(messages);
 * }</pre>
 */
public final class MessageExpiration {

    private final ScheduledExecutorService scheduler;

    private MessageExpiration(long cleanupIntervalMs) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "msg-expiration-cleaner");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> {}, cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new MessageExpiration manager.
     *
     * @param cleanupIntervalMs background cleanup interval in milliseconds
     * @return a new manager instance
     */
    public static MessageExpiration create(long cleanupIntervalMs) {
        return new MessageExpiration(cleanupIntervalMs);
    }

    /**
     * Wraps a message with a TTL, recording the creation time.
     *
     * @param message the message to wrap
     * @param ttlMs time-to-live in milliseconds
     * @param <M> the message type
     * @return an expiring wrapper around the message
     */
    public <M> ExpiringMessage<M> withExpiration(M message, long ttlMs) {
        return new ExpiringMessage<>(message, ttlMs, Instant.now());
    }

    /**
     * Returns {@code true} if the message has exceeded its TTL.
     *
     * @param msg the expiring message to check
     * @param <M> the message type
     * @return {@code true} if expired
     */
    public <M> boolean isExpired(ExpiringMessage<M> msg) {
        return ageMs(msg) > msg.ttlMs();
    }

    /**
     * Returns the age of the message in milliseconds.
     *
     * @param msg the expiring message
     * @param <M> the message type
     * @return age in milliseconds
     */
    public <M> long ageMs(ExpiringMessage<M> msg) {
        return Instant.now().toEpochMilli() - msg.createdAt().toEpochMilli();
    }

    /**
     * Returns milliseconds until the message expires (negative if already expired).
     *
     * @param msg the expiring message
     * @param <M> the message type
     * @return milliseconds remaining (may be negative)
     */
    public <M> long timeUntilExpiration(ExpiringMessage<M> msg) {
        return msg.ttlMs() - ageMs(msg);
    }

    /**
     * Removes all expired messages from the list in-place and returns them.
     *
     * @param messages a mutable list of expiring messages
     * @param <M> the message type
     * @return the removed (expired) messages
     */
    public <M> List<ExpiringMessage<M>> extractExpired(List<ExpiringMessage<M>> messages) {
        var expired = new ArrayList<ExpiringMessage<M>>();
        Iterator<ExpiringMessage<M>> it = messages.iterator();
        while (it.hasNext()) {
            var msg = it.next();
            if (isExpired(msg)) {
                expired.add(msg);
                it.remove();
            }
        }
        return expired;
    }

    /**
     * Returns a new list containing only the non-expired messages; the original list is unchanged.
     *
     * @param messages a list of expiring messages
     * @param <M> the message type
     * @return non-expired messages
     */
    public <M> List<ExpiringMessage<M>> retainNonExpired(List<ExpiringMessage<M>> messages) {
        return messages.stream().filter(m -> !isExpired(m)).toList();
    }

    /**
     * Shuts down the background cleanup scheduler.
     */
    public void close() {
        scheduler.shutdownNow();
    }

    /**
     * A message with an associated TTL and creation timestamp.
     *
     * @param message the wrapped message payload
     * @param ttlMs time-to-live in milliseconds
     * @param createdAt the instant this wrapper was created
     * @param <M> the message type
     */
    public record ExpiringMessage<M>(M message, long ttlMs, Instant createdAt) {

        /**
         * Returns the absolute expiry instant.
         *
         * @return {@code createdAt + ttlMs}
         */
        public Instant expiresAt() {
            return createdAt.plusMillis(ttlMs);
        }
    }
}
