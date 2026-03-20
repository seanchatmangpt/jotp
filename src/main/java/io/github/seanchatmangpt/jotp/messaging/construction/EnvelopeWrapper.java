package io.github.seanchatmangpt.jotp.messaging.construction;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope Wrapper pattern: wraps a domain message with transport metadata.
 *
 * <p>Enterprise Integration Pattern: <em>Envelope Wrapper</em> (EIP §6.5). The envelope separates
 * transport concerns (headers, routing, security) from the domain payload.
 *
 * <p>Headers enable stateless routing, distributed tracing, SLA enforcement, and idempotent retry
 * semantics in loosely-coupled services.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // Wrap with headers
 * var envelope = EnvelopeWrapper.wrap(
 *     new PlaceOrder("ord-1", 99.95),
 *     Map.of("correlation-id", "trace-123", "priority", "high")
 * );
 *
 * // Or use functional builder
 * var envelope = EnvelopeWrapper.wrap(
 *     payload,
 *     headers -> {
 *         headers.put("source", "order-service");
 *         headers.put("version", "1.0");
 *     }
 * );
 *
 * // Access payload and headers
 * PlaceOrder order = envelope.unwrap();
 * String correlationId = envelope.getCorrelationId();
 * String priority = envelope.getPriority();
 * }</pre>
 *
 * @param <T> the domain message type
 */
public final class EnvelopeWrapper {

    private EnvelopeWrapper() {}

    /**
     * An immutable envelope wrapping a payload with transport metadata.
     *
     * @param <T> the payload type
     */
    public static final class Envelope<T> {

        private final UUID id;
        private final long timestamp;
        private final Map<String, String> headers;
        private final T payload;

        /**
         * Compact constructor with validation.
         *
         * @param id the unique envelope ID (must not be null)
         * @param timestamp creation time in epoch millis
         * @param headers transport metadata (must not be null)
         * @param payload the wrapped domain message (must not be null)
         * @throws IllegalArgumentException if id, headers, or payload is null
         */
        public Envelope(UUID id, long timestamp, Map<String, String> headers, T payload) {
            if (id == null) throw new IllegalArgumentException("id must not be null");
            if (headers == null) throw new IllegalArgumentException("headers must not be null");
            if (payload == null) throw new IllegalArgumentException("payload must not be null");
            this.id = id;
            this.timestamp = timestamp;
            this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
            this.payload = payload;
        }

        /** Returns the unique envelope ID. */
        public UUID id() {
            return id;
        }

        /** Returns the creation timestamp in epoch milliseconds. */
        public long timestamp() {
            return timestamp;
        }

        /** Returns an immutable view of the headers. */
        public Map<String, String> headers() {
            return headers;
        }

        /** Returns the unwrapped payload. */
        public T payload() {
            return payload;
        }

        /** Returns the unwrapped payload. Alias for {@link #payload()}. */
        public T unwrap() {
            return payload;
        }

        /**
         * Returns a header value by key.
         *
         * @param key the header key
         * @return the header value, or {@code null} if not present
         */
        public String getHeader(String key) {
            return headers.get(key);
        }

        /**
         * Returns a header value with a fallback default.
         *
         * @param key the header key
         * @param defaultValue the value to return if key is not present
         * @return the header value or defaultValue
         */
        public String getHeaderOrDefault(String key, String defaultValue) {
            return headers.getOrDefault(key, defaultValue);
        }

        /**
         * Returns {@code true} if a header is present.
         *
         * @param key the header key to check
         * @return {@code true} if the header exists
         */
        public boolean hasHeader(String key) {
            return headers.containsKey(key);
        }

        /**
         * Returns a new envelope with additional headers.
         *
         * @param newHeaders headers to add
         * @return a new envelope with merged headers
         */
        public Envelope<T> withHeaders(Map<String, String> newHeaders) {
            var merged = new HashMap<>(headers);
            merged.putAll(newHeaders);
            return new Envelope<>(id, timestamp, merged, payload);
        }

        /**
         * Returns a new envelope with an additional single header.
         *
         * @param key the header key
         * @param value the header value
         * @return a new envelope with the added header
         */
        public Envelope<T> withHeader(String key, String value) {
            var updated = new HashMap<>(headers);
            updated.put(key, value);
            return new Envelope<>(id, timestamp, updated, payload);
        }

        /**
         * Returns the correlation ID from headers, falling back to envelope ID as string.
         *
         * @return the correlation ID (never null)
         */
        public String getCorrelationId() {
            String corrId = headers.get("correlation-id");
            return corrId != null ? corrId : id.toString();
        }

        /**
         * Returns the request ID for idempotency.
         *
         * @return the request ID, or null if not set
         */
        public String getRequestId() {
            return headers.get("request-id");
        }

        /**
         * Returns the deadline for SLA enforcement.
         *
         * @return the deadline, or null if not set
         */
        public String getDeadline() {
            return headers.get("deadline");
        }

        /**
         * Returns the priority level.
         *
         * @return the priority, or null if not set
         */
        public String getPriority() {
            return headers.get("priority");
        }
    }

    /**
     * Wraps a payload with no headers.
     *
     * @param payload the domain message (must not be null)
     * @param <T> the payload type
     * @return a new envelope with empty headers
     * @throws IllegalArgumentException if payload is null
     */
    public static <T> Envelope<T> wrap(T payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        return new Envelope<>(UUID.randomUUID(), System.currentTimeMillis(), Map.of(), payload);
    }

    /**
     * Wraps a payload with the given headers.
     *
     * @param payload the domain message (must not be null)
     * @param headers transport metadata (must not be null)
     * @param <T> the payload type
     * @return a new envelope
     * @throws IllegalArgumentException if payload is null
     */
    public static <T> Envelope<T> wrap(T payload, Map<String, String> headers) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        Objects.requireNonNull(headers, "headers must not be null");
        return new Envelope<>(UUID.randomUUID(), System.currentTimeMillis(), headers, payload);
    }

    /**
     * Wraps a payload with headers built via a consumer function.
     *
     * @param payload the domain message (must not be null)
     * @param headerBuilder function to populate headers
     * @param <T> the payload type
     * @return a new envelope
     * @throws IllegalArgumentException if payload is null
     */
    public static <T> Envelope<T> wrap(
            T payload, java.util.function.Consumer<Map<String, String>> headerBuilder) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        Objects.requireNonNull(headerBuilder, "headerBuilder must not be null");
        var headers = new HashMap<String, String>();
        headerBuilder.accept(headers);
        return new Envelope<>(UUID.randomUUID(), System.currentTimeMillis(), headers, payload);
    }

    /**
     * Unwraps an envelope to retrieve its payload.
     *
     * @param envelope the envelope to unwrap
     * @param <T> the payload type
     * @return the unwrapped payload
     */
    public static <T> T unwrap(Envelope<T> envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        return envelope.payload();
    }

    /**
     * Returns a new envelope with replaced headers.
     *
     * @param envelope the original envelope
     * @param newHeaders the new headers to use
     * @param <T> the payload type
     * @return a new envelope with replaced headers
     */
    public static <T> Envelope<T> replaceHeaders(
            Envelope<T> envelope, Map<String, String> newHeaders) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(newHeaders, "newHeaders must not be null");
        return new Envelope<>(envelope.id(), envelope.timestamp(), newHeaders, envelope.payload());
    }
}
