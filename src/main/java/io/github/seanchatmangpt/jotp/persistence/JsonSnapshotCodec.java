package io.github.seanchatmangpt.jotp.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON-based implementation of {@link SnapshotCodec} using Jackson.
 *
 * <p>This codec provides human-readable, language-agnostic serialization of state snapshots using
 * JSON format. It supports Java records, Java time types (Instant, Duration, etc.), collections,
 * and most standard Java types.
 *
 * <p><strong>Thread Safety:</strong> This implementation is thread-safe and can be shared across
 * multiple processes. The underlying ObjectMapper is configured as thread-safe.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>Support for Java records (automatic serialization)
 *   <li>Java Time API support (Instant, Duration, LocalDateTime, etc.)
 *   <li>Collections (List, Set, Map)
 *   <li>Null handling
 *   <li>Unicode and special characters
 *   <li>Compact JSON output (no pretty printing)
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Create codec
 * SnapshotCodec<String> codec = new JsonSnapshotCodec();
 *
 * // Encode state
 * String state = "Hello, World!";
 * byte[] jsonBytes = codec.encode(state);
 *
 * // Decode state
 * String restored = codec.decode(jsonBytes, String.class);
 * }</pre>
 *
 * @see SnapshotCodec
 */
public class JsonSnapshotCodec implements SnapshotCodec<Object> {

    private static final String CONTENT_TYPE = "application/json";

    private final ObjectMapper objectMapper;

    /** Creates a new JsonSnapshotCodec with default ObjectMapper configuration. */
    public JsonSnapshotCodec() {
        this.objectMapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .disable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Creates a new JsonSnapshotCodec with a custom ObjectMapper.
     *
     * <p>This allows advanced configuration (custom serializers, deserializers, mixins, etc.)
     *
     * @param objectMapper the configured ObjectMapper to use
     * @throws IllegalArgumentException if objectMapper is null
     */
    public JsonSnapshotCodec(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper must not be null");
        }
        this.objectMapper = objectMapper;
    }

    /**
     * Encode a state object to JSON bytes.
     *
     * @param value the state object to encode, must not be null
     * @return the JSON-encoded byte array (UTF-8 encoded)
     * @throws CodecException if encoding fails (JsonProcessingException wrapped)
     * @throws IllegalArgumentException if value is null
     */
    @Override
    public byte[] encode(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot encode null value");
        }

        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new CodecException("Failed to encode state to JSON", e);
        }
    }

    /**
     * Decode JSON bytes back to a state object.
     *
     * <p>This variant accepts a Class parameter for type-safe deserialization.
     *
     * @param <T> the type of object to decode
     * @param data the JSON byte array to decode, must not be null or empty
     * @param type the Class object for the target type
     * @return the decoded state object
     * @throws CodecException if decoding fails
     * @throws IllegalArgumentException if data or type is null/empty
     */
    public <T> T decode(byte[] data, Class<T> type) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Cannot decode null or empty data");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type must not be null");
        }

        try {
            return objectMapper.readValue(data, type);
        } catch (IOException e) {
            throw new CodecException("Failed to decode JSON to state", e);
        }
    }

    /**
     * Decode JSON bytes back to a state object.
     *
     * <p>This variant attempts to infer the type from the JSON structure. For type-safe
     * deserialization, use {@link #decode(byte[], Class)} instead.
     *
     * @param data the JSON byte array to decode
     * @return the decoded state object (as Object)
     * @throws CodecException if decoding fails
     * @throws IllegalArgumentException if data is null or empty
     */
    @Override
    public Object decode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Cannot decode null or empty data");
        }

        try {
            String json = new String(data, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, Object.class);
        } catch (IOException e) {
            throw new CodecException("Failed to decode JSON to state", e);
        }
    }

    /**
     * Get the content type of the encoded data.
     *
     * @return {@code "application/json"}
     */
    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Check if the data appears to be valid JSON.
     *
     * <p>Implementation checks for non-null/non-empty data that starts with '{' or '[' (JSON object
     * or array).
     *
     * @param data the byte array to check
     * @return true if the data appears to be JSON
     */
    @Override
    public boolean canDecode(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        // Check if data starts with JSON object or array marker
        char firstChar = (char) data[0];
        return firstChar == '{' || firstChar == '[';
    }

    /**
     * Get the underlying ObjectMapper for advanced configuration.
     *
     * <p>This allows customizing serialization behavior after construction.
     *
     * @return the ObjectMapper used by this codec
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
