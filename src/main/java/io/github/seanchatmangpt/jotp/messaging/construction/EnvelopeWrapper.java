package io.github.seanchatmangpt.jotp.messaging.construction;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Envelope Wrapper: wraps a message payload with transport metadata headers.
 *
 * <p>Enterprise Integration Pattern: <em>Envelope Wrapper</em> (EIP §6.5). The envelope separates
 * transport concerns (routing, tracing, SLA, idempotency) from the domain payload. All headers are
 * immutable after construction; mutation returns new envelope copies.
 */
public final class EnvelopeWrapper {

    private EnvelopeWrapper() {}

    /**
     * An immutable envelope containing a payload and transport headers.
     *
     * @param id the envelope's unique ID
     * @param timestamp the creation timestamp (epoch millis)
     * @param headers the immutable transport headers
     * @param payload the wrapped domain payload
     * @param <T> the payload type
     */
    public record Envelope<T>(UUID id, long timestamp, Map<String, String> headers, T payload) {

        /** Compact constructor validating all fields. */
        public Envelope {
            if (id == null) {
                throw new IllegalArgumentException("id must not be null");
            }
            if (headers == null) {
                throw new IllegalArgumentException("headers must not be null");
            }
            if (payload == null) {
                throw new IllegalArgumentException("payload must not be null");
            }
            // Ensure headers are always unmodifiable
            headers = Collections.unmodifiableMap(new HashMap<>(headers));
        }

        /**
         * Returns the header value for the given key, or {@code null} if absent.
         *
         * @param key the header key
         * @return the header value, or {@code null}
         */
        public String getHeader(String key) {
            return headers.get(key);
        }

        /**
         * Returns the header value for the given key, or the default value if absent.
         *
         * @param key the header key
         * @param defaultValue the fallback value
         * @return the header value or default
         */
        public String getHeaderOrDefault(String key, String defaultValue) {
            return headers.getOrDefault(key, defaultValue);
        }

        /**
         * Returns {@code true} if the envelope contains a header with the given key.
         *
         * @param key the header key to check
         * @return {@code true} if present
         */
        public boolean hasHeader(String key) {
            return headers.containsKey(key);
        }

        /**
         * Returns the payload.
         *
         * @return the wrapped payload
         */
        public T unwrap() {
            return payload;
        }

        /**
         * Returns a new envelope with all existing headers plus the additional ones. Existing
         * headers are preserved.
         *
         * @param additionalHeaders headers to merge in
         * @return a new envelope with merged headers
         */
        public Envelope<T> withHeaders(Map<String, String> additionalHeaders) {
            Map<String, String> merged = new HashMap<>(headers);
            merged.putAll(additionalHeaders);
            return new Envelope<>(id, timestamp, merged, payload);
        }

        /**
         * Returns a new envelope with one additional header.
         *
         * @param key the header key
         * @param value the header value
         * @return a new envelope with the header added
         */
        public Envelope<T> withHeader(String key, String value) {
            Map<String, String> merged = new HashMap<>(headers);
            merged.put(key, value);
            return new Envelope<>(id, timestamp, merged, payload);
        }

        /** Returns the value of the {@code correlation-id} header, or the envelope ID as string. */
        public String getCorrelationId() {
            String value = headers.get("correlation-id");
            return value != null ? value : id.toString();
        }

        /** Returns the value of the {@code request-id} header, or {@code null} if absent. */
        public String getRequestId() {
            return headers.get("request-id");
        }

        /** Returns the value of the {@code deadline} header, or {@code null} if absent. */
        public String getDeadline() {
            return headers.get("deadline");
        }

        /** Returns the value of the {@code priority} header, or {@code null} if absent. */
        public String getPriority() {
            return headers.get("priority");
        }
    }

    /**
     * Wraps a payload with no headers.
     *
     * @param payload the domain payload (must not be null)
     * @param <T> the payload type
     * @return a new envelope
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
     * @param payload the domain payload (must not be null)
     * @param headers the transport headers
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
     * Wraps a payload, allowing a builder consumer to populate headers.
     *
     * @param payload the domain payload (must not be null)
     * @param headerBuilder a consumer that populates a mutable header map
     * @param <T> the payload type
     * @return a new envelope with the populated headers
     * @throws IllegalArgumentException if payload is null
     */
    public static <T> Envelope<T> wrap(T payload, Consumer<Map<String, String>> headerBuilder) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        Objects.requireNonNull(headerBuilder, "headerBuilder must not be null");
        Map<String, String> headers = new HashMap<>();
        headerBuilder.accept(headers);
        return new Envelope<>(UUID.randomUUID(), System.currentTimeMillis(), headers, payload);
    }

    /**
     * Extracts the payload from an envelope.
     *
     * @param envelope the envelope to unwrap
     * @param <T> the payload type
     * @return the payload
     */
    public static <T> T unwrap(Envelope<T> envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        return envelope.payload();
    }

    /**
     * Returns a new envelope with the given headers replacing all existing headers.
     *
     * @param envelope the source envelope
     * @param newHeaders the replacement headers
     * @param <T> the payload type
     * @return a new envelope with replaced headers
     */
    public static <T> Envelope<T> replaceHeaders(
            Envelope<T> envelope, Map<String, String> newHeaders) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(newHeaders, "newHeaders must not be null");
        return new Envelope<>(
                envelope.id(), envelope.timestamp(), newHeaders, envelope.payload());
    }
}
