package io.github.seanchatmangpt.jotp.reactive;

/**
 * Common interface for all message channels in the reactive package.
 *
 * <p>Any component that can accept a message for delivery implements this interface, enabling
 * channel composition (e.g., WireTap wrapping a PointToPointChannel, or DynamicRouter delegating to
 * downstream channels).
 *
 * @param <T> message type
 */
public interface MessageChannel<T> {

    /**
     * Sends a message into this channel for processing or forwarding.
     *
     * @param message the message to send
     */
    void send(T message);
}
