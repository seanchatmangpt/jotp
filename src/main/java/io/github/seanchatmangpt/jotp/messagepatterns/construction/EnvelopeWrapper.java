package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import java.time.Instant;
import java.util.Map;

/**
 * Envelope Wrapper pattern: wraps a domain message with transport metadata.
 *
 * <p>Enterprise Integration Pattern: <em>Envelope Wrapper</em> (EIP §6.5). The envelope separates
 * transport concerns (headers, routing, security) from the domain payload.
 *
 * <p>Erlang analog: tagged tuples with metadata headers — e.g., {@code {envelope, Headers, Body}}
 * where Headers is a proplist of transport metadata.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, the
 * envelope wraps domain messages with protocol-specific transport information (e.g., RabbitMQ
 * reply-to headers).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * record PlaceOrder(String orderId, double amount) {}
 *
 * var envelope = EnvelopeWrapper.wrap(
 *     new PlaceOrder("ord-1", 99.95),
 *     Map.of("content-type", "application/json", "reply-to", "queue://replies")
 * );
 *
 * PlaceOrder payload = envelope.unwrap();
 * String contentType = envelope.header("content-type");
 * }</pre>
 *
 * @param payload the wrapped domain message
 * @param headers transport/protocol metadata
 * @param messageId unique message identifier
 * @param timestamp when the envelope was created
 * @param <T> the domain message type
 */
public record EnvelopeWrapper<T>(
        T payload, Map<String, String> headers, String messageId, Instant timestamp) {

    /** Canonical constructor ensuring immutable headers. */
    public EnvelopeWrapper {
        headers = Map.copyOf(headers);
    }

    /**
     * Wrap a payload with the given headers.
     *
     * @param payload the domain message
     * @param headers transport metadata
     * @param <T> payload type
     * @return a new envelope
     */
    public static <T> EnvelopeWrapper<T> wrap(T payload, Map<String, String> headers) {
        return new EnvelopeWrapper<>(
                payload, headers, CorrelationIdentifier.create().id().toString(), Instant.now());
    }

    /**
     * Wrap a payload with no headers.
     *
     * @param payload the domain message
     * @param <T> payload type
     * @return a new envelope with empty headers
     */
    public static <T> EnvelopeWrapper<T> wrap(T payload) {
        return wrap(payload, Map.of());
    }

    /**
     * Extract the domain message from the envelope.
     *
     * @return the unwrapped payload
     */
    public T unwrap() {
        return payload;
    }

    /**
     * Get a header value by key.
     *
     * @param key the header key
     * @return the header value, or null if not present
     */
    public String header(String key) {
        return headers.get(key);
    }

    /**
     * Create a new envelope with an additional header.
     *
     * @param key the header key
     * @param value the header value
     * @return a new envelope with the added header
     */
    public EnvelopeWrapper<T> withHeader(String key, String value) {
        var updated = new java.util.HashMap<>(headers);
        updated.put(key, value);
        return new EnvelopeWrapper<>(payload, updated, messageId, timestamp);
    }
}
