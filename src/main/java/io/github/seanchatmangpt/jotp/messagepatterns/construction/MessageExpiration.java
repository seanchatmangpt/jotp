package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import java.time.Duration;
import java.time.Instant;

/**
 * Message Expiration pattern: a message carries a time-to-live; expired messages are discarded.
 *
 * <p>Enterprise Integration Pattern: <em>Message Expiration</em> (EIP §6.5). Erlang analog: {@code
 * timer:send_after/3} with an expiry check in the receive clause — stale messages are dropped from
 * the mailbox.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * PlaceOrder} implements an {@code ExpiringMessage} trait that checks {@code
 * System.currentTimeMillis} against a TTL.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * record PlaceOrder(String id, double price, Instant occurredOn, Duration timeToLive)
 *     implements MessageExpiration {
 *     PlaceOrder(String id, double price, Duration ttl) {
 *         this(id, price, Instant.now(), ttl);
 *     }
 * }
 *
 * var order = new PlaceOrder("ord-1", 99.95, Duration.ofSeconds(5));
 * if (!order.isExpired()) {
 *     processor.tell(order);
 * }
 * }</pre>
 */
public interface MessageExpiration {

    /** When the message was created. */
    Instant occurredOn();

    /** Maximum lifetime of this message. */
    Duration timeToLive();

    /**
     * Check if this message has expired.
     *
     * @return true if the message's TTL has elapsed since creation
     */
    default boolean isExpired() {
        return Instant.now().isAfter(occurredOn().plus(timeToLive()));
    }

    /**
     * Returns the expiration instant.
     *
     * @return the instant at which this message expires
     */
    default Instant expiresAt() {
        return occurredOn().plus(timeToLive());
    }
}
