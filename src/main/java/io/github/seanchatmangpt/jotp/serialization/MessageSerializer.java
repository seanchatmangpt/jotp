package io.github.seanchatmangpt.jotp.serialization;

/**
 * Service Provider Interface (SPI) for message serialization in JOTP.
 *
 * <p>This interface allows developers to plug in custom serialization strategies, enabling flexible
 * network transport and message format choices. JOTP provides built-in implementations for Jackson
 * (JSON) and Protobuf (binary), but users can implement custom serializers for formats like Kryo,
 * FST, or application-specific binary protocols.
 *
 * <p><strong>Serialization Philosophy:</strong> Network transport is pluggable. JOTP doesn't
 * mandate gRPC or any specific format. This SPI defines the contract: serialize message to bytes,
 * send to remote node, deserialize on the other side. The format choice (JSON, protobuf, Kryo,
 * etc.) is entirely up to the developer.
 *
 * <p><strong>Mapping to OTP Design:</strong>
 *
 * <p>In Erlang/OTP, messages are transmitted in the Erlang external term format (ETF), a
 * self-describing binary encoding. Java developers have different needs and ecosystem preferences.
 * This SPI models the same concept: a message-to-bytes transformer that is independent of the
 * network protocol.
 *
 * <p><strong>Usage Examples:</strong>
 *
 * <pre>{@code
 * // Using built-in Jackson serializer
 * var jacksonSerializer = new JacksonMessageSerializer()
 *     .registerType("com.example.UserCreated", UserCreated.class)
 *     .registerType("com.example.UserDeleted", UserDeleted.class);
 *
 * // Serialize a message
 * UserCreated event = new UserCreated("user-123", "alice@example.com");
 * byte[] json = jacksonSerializer.serialize(event);
 *
 * // Deserialize back
 * Object restored = jacksonSerializer.deserialize(json, "com.example.UserCreated");
 *
 * // Using protobuf serializer
 * var protobufSerializer = new ProtobufMessageSerializer()
 *     .registerType("com.example.OrderPlaced", OrderPlaced.class);
 *
 * // Using custom serializer
 * var kryoSerializer = new KryoMessageSerializer();
 *
 * // Configure at application startup
 * ApplicationController.configureSerializer(jacksonSerializer);
 * }</pre>
 *
 * <p><strong>Type Registry:</strong>
 *
 * <p>Serializers maintain a registry of message types that can be serialized/deserialized. This
 * allows the framework to validate at startup that all message types are supported, providing clear
 * error messages if a message type is unknown.
 *
 * <p><strong>Thread Safety:</strong>
 *
 * <p>All implementations must be thread-safe. Serialization and deserialization may occur
 * concurrently from multiple process threads.
 *
 * @since 1.0
 * @author JOTP Contributors
 * @see JacksonMessageSerializer
 * @see ProtobufMessageSerializer
 */
public interface MessageSerializer {
  /**
   * Serialize a message object to bytes.
   *
   * <p>The serialized format depends on the implementation (JSON, protobuf, Kryo, etc.). The
   * serializer must handle the given message type or throw {@link SerializationException}.
   *
   * <p>This method is called:
   * <ul>
   *   <li>When sending messages to remote processes
   *   <li>When storing messages in event logs
   *   <li>When persisting messages in distributed saga coordinators
   * </ul>
   *
   * @param message the message object to serialize (typically a sealed record or enum)
   * @return serialized bytes
   * @throws SerializationException if the message type is not supported or serialization fails
   * @throws NullPointerException if {@code message} is null
   */
  byte[] serialize(Object message) throws SerializationException;

  /**
   * Deserialize bytes back into a message object.
   *
   * <p>The deserialized type is determined by the {@code messageType} parameter, which is
   * typically the fully-qualified class name (e.g., "com.example.UserCreated"). The serializer
   * uses its type registry to find the corresponding Java class.
   *
   * <p>This method is called:
   * <ul>
   *   <li>When receiving messages from remote processes
   *   <li>When reading messages from event logs
   *   <li>When replaying distributed saga events
   * </ul>
   *
   * @param data the serialized bytes
   * @param messageType the expected message type (e.g., fully-qualified class name)
   * @return the deserialized message object
   * @throws SerializationException if the message type is unknown or deserialization fails
   * @throws NullPointerException if {@code data} or {@code messageType} is null
   */
  Object deserialize(byte[] data, String messageType) throws SerializationException;

  /**
   * Check if this serializer can handle a given message type.
   *
   * <p>Used during application startup to validate that all message types are registered with at
   * least one serializer. If a message type is not supported by any serializer, an error is raised.
   *
   * @param messageType the message type identifier (e.g., fully-qualified class name)
   * @return {@code true} if this serializer can serialize/deserialize the type, {@code false}
   *     otherwise
   */
  boolean supportsType(String messageType);

  /**
   * Get the human-readable format name for this serializer.
   *
   * <p>Used for logging, metrics, and configuration. Examples: "json", "protobuf", "kryo".
   *
   * @return the format name (never null)
   */
  String formatName();
}
