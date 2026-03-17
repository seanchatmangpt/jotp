package io.github.seanchatmangpt.jotp.serialization;

import com.google.protobuf.Message;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol Buffer (protobuf) message serializer for JOTP.
 *
 * <p>This serializer provides efficient binary encoding using Google's Protocol Buffers format.
 * It is the recommended choice for production deployments where bandwidth and latency are critical.
 *
 * <p><strong>Format:</strong> Binary (compact, efficient wire format)
 *
 * <p><strong>Use Cases:</strong>
 *
 * <ul>
 *   <li><strong>High-Performance Networks:</strong> Smallest message size, fastest serialization
 *   <li><strong>Mobile/IoT:</strong> Bandwidth-sensitive environments
 *   <li><strong>Microservices:</strong> When paired with gRPC for distributed communication
 *   <li><strong>Production Systems:</strong> Proven stability and backward compatibility
 * </ul>
 *
 * <p><strong>Performance Characteristics:</strong>
 *
 * <ul>
 *   <li><strong>Serialization:</strong> Fastest of all formats (2-10x faster than JSON)
 *   <li><strong>Deserialization:</strong> Fastest of all formats
 *   <li><strong>Size:</strong> Smallest message size (60-80% smaller than JSON)
 *   <li><strong>Thread Safety:</strong> Fully thread-safe
 * </ul>
 *
 * <p><strong>Setup Requirements:</strong>
 *
 * <p>Unlike Jackson, protobuf requires Java code generation from `.proto` files. The typical
 * workflow is:
 *
 * <ol>
 *   <li>Define message schemas in `.proto` files (in {@code src/main/proto/})
 *   <li>Run protoc compiler (via Maven plugin) to generate Java classes
 *   <li>Register generated classes with this serializer
 * </ol>
 *
 * <p><strong>Example Setup:</strong>
 *
 * <pre>{@code
 * // In src/main/proto/user.proto:
 * syntax = "proto3";
 * package com.example;
 *
 * message UserCreated {
 *   string user_id = 1;
 *   string email = 2;
 * }
 *
 * // Maven plugin configuration (in pom.xml):
 * <plugin>
 *     <groupId>org.xolstice.maven.plugins</groupId>
 *     <artifactId>protobuf-maven-plugin</artifactId>
 *     <version>0.6.1</version>
 *     <configuration>
 *         <protocArtifact>com.google.protobuf:protoc:3.24.0:exe:${os.detected.classifier}</protocArtifact>
 *     </configuration>
 * </plugin>
 * }</pre>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * // Register generated classes (auto-generated from .proto files)
 * var serializer = new ProtobufMessageSerializer();
 * serializer.registerType("com.example.UserCreated", UserCreated.class);
 * serializer.registerType("com.example.UserDeleted", UserDeleted.class);
 *
 * // Serialize
 * UserCreated event = UserCreated.newBuilder()
 *     .setUserId("user-123")
 *     .setEmail("alice@example.com")
 *     .build();
 * byte[] protobuf = serializer.serialize(event);
 *
 * // Deserialize
 * Object restored = serializer.deserialize(protobuf, "com.example.UserCreated");
 * }</pre>
 *
 * <p><strong>Backward Compatibility:</strong>
 *
 * <p>Protobuf's wire format supports evolution: new fields can be added, old fields can be
 * deprecated, all while maintaining compatibility with older clients. This makes protobuf ideal
 * for long-lived systems that need version-independent message passing.
 *
 * @since 1.0
 * @author JOTP Contributors
 * @see <a href="https://developers.google.com/protocol-buffers">Protocol Buffers Documentation</a>
 */
public class ProtobufMessageSerializer implements MessageSerializer {
  private final Map<String, Class<? extends Message>> typeRegistry;

  /**
   * Create a protobuf serializer with an empty type registry.
   *
   * <p>Message types must be registered via {@link #registerType} before use.
   */
  public ProtobufMessageSerializer() {
    this.typeRegistry = new ConcurrentHashMap<>();
  }

  /**
   * Register a protobuf message type with its corresponding class.
   *
   * <p>The class must be a generated protobuf Message class (implements {@code
   * com.google.protobuf.Message}).
   *
   * @param typeName the type identifier (e.g., "com.example.UserCreated")
   * @param clazz the protobuf message class (must be a protobuf Message)
   * @return this serializer for method chaining
   * @throws NullPointerException if {@code typeName} or {@code clazz} is null
   * @throws IllegalArgumentException if {@code clazz} is not a protobuf Message class
   */
  public ProtobufMessageSerializer registerType(
      String typeName, Class<? extends Message> clazz) {
    if (typeName == null) {
      throw new NullPointerException("typeName must not be null");
    }
    if (clazz == null) {
      throw new NullPointerException("clazz must not be null");
    }
    if (!Message.class.isAssignableFrom(clazz)) {
      throw new IllegalArgumentException(
          "Class " + clazz.getName() + " does not implement com.google.protobuf.Message");
    }
    typeRegistry.put(typeName, clazz);
    return this;
  }

  /**
   * Serialize a protobuf message to bytes.
   *
   * <p>The message must implement {@code com.google.protobuf.Message} (i.e., it must be a
   * protobuf-generated class).
   *
   * @param message the protobuf message to serialize (must not be null)
   * @return the serialized bytes
   * @throws SerializationException if serialization fails or message is not a protobuf Message
   * @throws NullPointerException if {@code message} is null
   */
  @Override
  public byte[] serialize(Object message) throws SerializationException {
    if (message == null) {
      throw new NullPointerException("message must not be null");
    }

    if (!(message instanceof Message protoMessage)) {
      throw new SerializationException(
          "Message must be a protobuf Message, got " + message.getClass().getName());
    }

    try {
      return protoMessage.toByteArray();
    } catch (Exception e) {
      throw new SerializationException(
          "Failed to serialize protobuf message: " + message.getClass().getName(), e);
    }
  }

  /**
   * Deserialize bytes back into a protobuf message.
   *
   * <p>Uses the protobuf-generated {@code parseFrom(byte[])} method to reconstruct the message.
   *
   * @param data the serialized bytes (must not be null)
   * @param messageType the type identifier (must not be null)
   * @return the deserialized protobuf message
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

    Class<? extends Message> clazz = typeRegistry.get(messageType);
    if (clazz == null) {
      throw new SerializationException("Unknown message type: " + messageType);
    }

    try {
      Method parseFrom = clazz.getMethod("parseFrom", byte[].class);
      return parseFrom.invoke(null, (Object) data);
    } catch (NoSuchMethodException e) {
      throw new SerializationException(
          "Class "
              + clazz.getName()
              + " does not have parseFrom(byte[]) method. Is it a protobuf-generated class?",
          e);
    } catch (InvocationTargetException e) {
      throw new SerializationException(
          "Failed to deserialize protobuf type " + messageType, e.getCause());
    } catch (IllegalAccessException e) {
      throw new SerializationException(
          "Cannot access parseFrom method on " + clazz.getName(), e);
    }
  }

  /**
   * Check if this serializer knows how to handle a message type.
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
   * @return the string "protobuf"
   */
  @Override
  public String formatName() {
    return "protobuf";
  }
}
