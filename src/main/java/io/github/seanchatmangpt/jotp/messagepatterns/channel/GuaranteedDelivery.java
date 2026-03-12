package io.github.seanchatmangpt.jotp.messagepatterns.channel;

import io.github.seanchatmangpt.jotp.ProcTimer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Guaranteed Delivery pattern: persists messages and retries delivery until acknowledged.
 *
 * <p>Enterprise Integration Pattern: <em>Guaranteed Delivery</em> (EIP §7.2). Messages are stored
 * before sending and only removed after the receiver acknowledges receipt.
 *
 * <p>Erlang analog: {@code gen_server} with persistent state and periodic retry — the process
 * maintains an outbox and re-sends unacknowledged messages on a timer.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, Akka
 * Persistence's {@code Processor} and {@code Channel} provide at-least-once delivery. Here we
 * implement a simpler in-memory version with retry via {@link ProcTimer}.
 *
 * @param <T> message type
 */
public final class GuaranteedDelivery<T> {

    /** A tracked delivery with unique ID. */
    public record PendingDelivery<T>(String deliveryId, T message, int attempts) {}

    private final ConcurrentHashMap<String, PendingDelivery<T>> pending = new ConcurrentHashMap<>();
    private final Consumer<T> deliveryTarget;
    private final Duration retryInterval;
    private final int maxRetries;
    private volatile ProcTimer.TimerRef retryTimer;

    /**
     * Creates a guaranteed delivery channel.
     *
     * @param deliveryTarget the consumer that receives messages
     * @param retryInterval how often to retry unacknowledged messages
     * @param maxRetries maximum delivery attempts before moving to dead letters
     */
    public GuaranteedDelivery(Consumer<T> deliveryTarget, Duration retryInterval, int maxRetries) {
        this.deliveryTarget = deliveryTarget;
        this.retryInterval = retryInterval;
        this.maxRetries = maxRetries;
    }

    /**
     * Send a message with guaranteed delivery.
     *
     * @param message the message to deliver
     * @return the delivery ID for acknowledgment
     */
    public String send(T message) {
        String deliveryId = UUID.randomUUID().toString();
        pending.put(deliveryId, new PendingDelivery<>(deliveryId, message, 1));
        deliveryTarget.accept(message);
        return deliveryId;
    }

    /**
     * Acknowledge receipt of a delivered message.
     *
     * @param deliveryId the delivery ID from send()
     * @return true if the delivery was pending and is now acknowledged
     */
    public boolean acknowledge(String deliveryId) {
        return pending.remove(deliveryId) != null;
    }

    /** Retry all pending unacknowledged deliveries. Returns IDs that exceeded max retries. */
    public List<String> retryPending() {
        var expired = new java.util.ArrayList<String>();
        for (Map.Entry<String, PendingDelivery<T>> entry : pending.entrySet()) {
            var delivery = entry.getValue();
            if (delivery.attempts() >= maxRetries) {
                expired.add(entry.getKey());
                pending.remove(entry.getKey());
            } else {
                pending.put(
                        entry.getKey(),
                        new PendingDelivery<>(
                                delivery.deliveryId(),
                                delivery.message(),
                                delivery.attempts() + 1));
                deliveryTarget.accept(delivery.message());
            }
        }
        return expired;
    }

    /** Returns the number of pending (unacknowledged) deliveries. */
    public int pendingCount() {
        return pending.size();
    }
}
