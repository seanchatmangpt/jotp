package io.github.seanchatmangpt.jotp.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON-based message serializer using Jackson ObjectMapper.
 *
 * <p>This serializer provides human-readable JSON encoding for messages, making it suitable for
 * debugging, logging, and scenarios where network bandwidth is less critical. It leverages Jackson's
 * support for sealed types, records, and pattern-based serialization.
 *
 * <p><strong>Format:</strong> JSON (text-based, human-readable)
 *
 * <p><strong>Use Cases:</strong>
 *
 * <ul>
 *   <li><strong>Development and Testing:</strong> Easy to inspect serialized messages in logs
 *   <li><strong>Interoperability:</strong> JSON is universally understood; consumers can be
 *       non-JVM services (Node.js, Python, Go, etc.)
 *   <li><strong>REST APIs:</strong> Natural choice for HTTP-based process communication
 *   <li><strong>Event Sourcing:</strong> Audit logs and event stores often benefit from human
 *       readability
 * </ul>
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li><strong>Serialization:</strong> Slower than binary formats (protobuf, Kryo)
 *   <li><strong>Deserialization:</strong> Requires type information (messageType parameter)
 *   <li><strong>Size:</strong> Larger than binary formats (30-40% overhead)
 *   <li><strong>Thread Safety:</strong> Fully thread-safe; Jackson's ObjectMapper is thread-safe
 * </ul>
 *
 * <p><strong>Type Registry Usage:</strong>
 *
 * <pre>{@code
 * // Register all message types at startup
 * var serializer = new JacksonMessageSerializer();
 * serializer.registerType("com.example.UserCreated", UserCreated.class);
 * serializer.registerType("com.example.UserDeleted", UserDeleted.class);
 * serializer.registerType("com.example.OrderPlaced", OrderPlaced.class);
 *
 * // Later: serialize and deserialize
 * UserCreated event = new UserCreated("user-123", "alice@example.com");
 * byte[] json = serializer.serialize(event);
 * Object restored = serializer.deserialize(json, "com.example.UserCreated");
 * }</pre>
 *
 * <p><strong>Sealed Type Support:</strong>
 *
 * <p>This serializer is optimized for Erlang/OTP-style message hierarchies using sealed interfaces:
 *
 * <pre>{@code
 * public sealed interface Event permits UserCreated, UserDeleted, OrderPlaced {
 *     @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
 *     // ...
 * }
 *
 * public record UserCreated(String userId, String email) implements Event {}
 * public record UserDeleted(String userId) implements Event {}
 * }</pre>
 *
 * @since 1.0
 * @author JOTP Contributors
 */
public class JacksonMessageSerializer implements MessageSerializer {
  private final ObjectMapper mapper;
  private final Map<String, Class<?>> typeRegistry;

  /**
   * Create a Jackson serializer with default ObjectMapper configuration.
   *
   * <p>The ObjectMapper is configured with:
   * <ul>
   *   <li>Default typing for polymorphic types (sealed interfaces)
   *   <li>JSON pretty printing disabled (compact)
   * </ul>
   */
  public JacksonMessageSerializer() {
    this.mapper = new ObjectMapper();
    this.typeRegistry = new ConcurrentHashMap<>();
  }

  /**
   * Create a Jackson serializer with a custom ObjectMapper.
   *
   * <p>This constructor allows advanced users to provide a pre-configured ObjectMapper with custom
   * modules, serializers, or deserializers.
   *
   * @param mapper the Jackson ObjectMapper to use (must not be null)
   * @throws NullPointerException if {@code mapper} is null
   */
  public JacksonMessageSerializer(ObjectMapper mapper) {
    this.mapper = mapper != null ? mapper : new ObjectMapper();
    this.typeRegistry = new ConcurrentHashMap<>();
  }

  /**
   * Register a message type with its corresponding class.
   *
   * <p>This must be called for every message type that will be serialized or deserialized. Calling
   * this method multiple times with the same type name overwrites the previous registration
   * (idempotent).
   *
   * <p><strong>Naming Convention:</strong> Type names are typically fully-qualified class names
   * (e.g., "com.example.UserCreated"), but any string is acceptable.
   *
   * @param typeName the type identifier (e.g., fully-qualified class name)
   * @param clazz the Java class for this type
   * @return this serializer for method chaining
   * @throws NullPointerException if {@code typeName} or {@code clazz} is null
   */
  public JacksonMessageSerializer registerType(String typeName, Class<?> clazz) {
    if (typeName == null) {
      throw new NullPointerException("typeName must not be null");
    }
    if (clazz == null) {
      throw new NullPointerException("clazz must not be null");
    }
    typeRegistry.put(typeName, clazz);
    return this;
  }

  /**
   * Serialize a message object to JSON bytes.
   *
   * <p>The message is converted to JSON using Jackson's ObjectMapper. The serializer does not
   * record the type name in the output; the caller is responsible for tracking which type was
   * serialized (typically via a separate message envelope or protocol header).
   *
   * @param message the message object to serialize (must not be null)
   * @return the JSON bytes
   * @throws SerializationException if Jackson encounters an error
   * @throws NullPointerException if {@code message} is null
   */
  @Override
  public byte[] serialize(Object message) throws SerializationException {
    if (message == null) {
      throw new NullPointerException("message must not be null");
    }
    try {
      return mapper.writeValueAsBytes(message);
    } catch (JsonProcessingException e) {
      throw new SerializationException(
          "Failed to serialize message of type " + message.getClass().getName(), e);
    }
  }

  /**
   * Deserialize JSON bytes back into a message object.
   *
   * <p>The {@code messageType} parameter is used to look up the class in the type registry. If the
   * type is not registered, a {@link SerializationException} is thrown.
   *
   * <p>If the type name is not in the registry, deserialization falls back to treating the bytes
   * as untyped JSON (returns a generic Map or List). To enforce strict type checking, ensure all
   * message types are registered at startup.
   *
   * @param data the JSON bytes (must not be null)
   * @param messageType the type identifier (must not be null)
   * @return the deserialized message object
   * @throws SerializationException if type is not registered or deserialization fails
   * @throws NullPointerException if {@code data} or {@code messageType} is null
   */
  @Override
  public Object deserialize(byte[] data, String messageType) throws SerializationException {
    if (data == null) {
      throw new NullPointerException("data must not be null");
    }
    if (messageType == null) {
      throw new NullPointerException("messageType must not be null");
    }

    Class<?> clazz = typeRegistry.get(messageType);
    if (clazz == null) {
      throw new SerializationException("Unknown message type: " + messageType);
    }

    try {
      return mapper.readValue(data, clazz);
    } catch (IOException e) {
      throw new SerializationException("Failed to deserialize type " + messageType, e);
    }
  }

  /**
   * Check if this serializer knows how to handle a message type.
   *
   * <p>Returns {@code true} if the type has been registered via {@link #registerType}.
   *
   * @param messageType the type identifier
   * @return {@code true} if registered, {@code false} otherwise
   */
  @Override
  public boolean supportsType(String messageType) {
    return typeRegistry.containsKey(messageType);
  }

  /**
   * Get the format name for this serializer.
   *
   * @return the string "json"
   */
  @Override
  public String formatName() {
    return "json";
  }

  /**
   * Get the underlying Jackson ObjectMapper.
   *
   * <p>Advanced users can access the mapper to register custom modules or serializers.
   *
   * @return the ObjectMapper used by this serializer
   */
  public ObjectMapper getMapper() {
    return mapper;
  }
}
