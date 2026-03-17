package io.github.seanchatmangpt.jotp.persistence;

/**
 * Codec for serializing and deserializing state snapshots.
 *
 * <p>Implementations provide bidirectional conversion between state objects and byte arrays. This
 * abstraction allows different serialization strategies (Java serialization, JSON, protobuf, etc.)
 * to be plugged into the persistence layer.
 *
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe and reusable across
 * multiple processes.
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Define a state record
 * record CounterState(int value, long lastModified) {}
 *
 * // Create codec implementation
 * class CounterCodec implements SnapshotCodec<CounterState> {
 *     public byte[] encode(CounterState state) {
 *         // Serialize to bytes
 *     }
 *
 *     public CounterState decode(byte[] data) {
 *         // Deserialize from bytes
 *     }
 * }
 *
 * // Use codec
 * SnapshotCodec<CounterState> codec = new CounterCodec();
 * byte[] data = codec.encode(new CounterState(42, System.currentTimeMillis()));
 * CounterState restored = codec.decode(data);
 * }</pre>
 *
 * @param <T> the type of object to serialize
 * @see JsonSnapshotCodec
 */
public interface SnapshotCodec<T> {

    /**
     * Encode a state object to a byte array.
     *
     * @param value the state object to encode, must not be null
     * @return the encoded byte array
     * @throws Exception if encoding fails
     * @throws IllegalArgumentException if value is null
     */
    byte[] encode(T value) throws Exception;

    /**
     * Decode a byte array back to a state object.
     *
     * @param data the byte array to decode, must not be null or empty
     * @return the decoded state object
     * @throws Exception if decoding fails
     * @throws IllegalArgumentException if data is null or empty
     * @throws CodecException if the data is malformed or incompatible
     */
    T decode(byte[] data) throws Exception;

    /**
     * Get the content type of the encoded data.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>{@code application/json} for JSON codecs
     *   <li>{@code application/octet-stream} for binary codecs
     *   <li>{@code application/x-protobuf} for protobuf codecs
     * </ul>
     *
     * @return the MIME type of the encoded data
     */
    String contentType();

    /**
     * Check if this codec can decode the given data.
     *
     * <p>Default implementation checks if data is non-null and non-empty. Implementations can
     * override to provide content-based detection (e.g., checking for magic bytes).
     *
     * @param data the byte array to check
     * @return true if the data appears decodable by this codec
     */
    default boolean canDecode(byte[] data) {
        return data != null && data.length > 0;
    }

    /**
     * Exception thrown when codec operations fail.
     *
     * <p>This exception wraps underlying serialization/deserialization errors (IO, class casting,
     * format errors, etc.) in a unified exception type.
     */
    class CodecException extends RuntimeException {

        /**
         * Create a codec exception with a message.
         *
         * @param message the error message
         */
        public CodecException(String message) {
            super(message);
        }

        /**
         * Create a codec exception with a message and cause.
         *
         * @param message the error message
         * @param cause the underlying cause
         */
        public CodecException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Create a codec exception from a cause.
         *
         * @param cause the underlying cause
         */
        public CodecException(Throwable cause) {
            super(cause);
        }
    }
}
