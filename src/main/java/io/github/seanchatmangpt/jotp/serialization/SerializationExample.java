package io.github.seanchatmangpt.jotp.serialization;

/**
 * Example demonstrating the Message Serialization SPI in JOTP.
 *
 * <p>This class shows how to:
 * <ol>
 *   <li>Create serializers (Jackson, Protobuf, custom)
 *   <li>Register message types
 *   <li>Serialize and deserialize messages
 *   <li>Use serializers with JOTP processes
 *   <li>Handle errors and edge cases
 * </ol>
 *
 * <p><strong>Quick Start:</strong>
 *
 * <pre>{@code
 * // 1. Create a Jackson serializer
 * var serializer = new JacksonMessageSerializer()
 *     .registerType("com.example.UserCreated", UserCreated.class)
 *     .registerType("com.example.UserDeleted", UserDeleted.class);
 *
 * // 2. Serialize a message
 * var event = new UserCreated("user-123", "alice@example.com");
 * byte[] json = serializer.serialize(event);
 *
 * // 3. Deserialize it back
 * UserCreated restored = (UserCreated) serializer.deserialize(json, "com.example.UserCreated");
 *
 * // 4. Send over network or store in database
 * remoteNode.send(json);
 * eventStore.append(json);
 * }</pre>
 *
 * @since 1.0
 * @author JOTP Contributors
 */
public final class SerializationExample {
  private SerializationExample() {}

  // ── Internal utilities ────────────────────────────────────────────────────

  private static byte[] javaSerialize(Object message) throws SerializationException {
    try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(baos)) {
      oos.writeObject(message);
      return baos.toByteArray();
    } catch (java.io.IOException e) {
      throw new SerializationException("Java serialization failed: " + e.getMessage(), e);
    }
  }

  private static Object javaDeserialize(byte[] data) throws SerializationException {
    try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais)) {
      return ois.readObject();
    } catch (java.io.IOException | ClassNotFoundException e) {
      throw new SerializationException("Java deserialization failed: " + e.getMessage(), e);
    }
  }

  /**
   * Example: Using Jackson for human-readable JSON serialization.
   *
   * <p>Best for:
   * <ul>
   *   <li>Development and debugging
   *   <li>Interoperability with non-JVM services
   *   <li>Event sourcing where readability is important
   * </ul>
   */
  public static void jacksonExample() {
    // Create serializer
    JacksonMessageSerializer serializer = new JacksonMessageSerializer();

    // Register message types from your domain
    serializer.registerType("io.example.UserCreated", Object.class);
    serializer.registerType("io.example.OrderPlaced", Object.class);

    // Check if a type is supported
    if (serializer.supportsType("io.example.UserCreated")) {
      System.out.println("UserCreated is registered");
    }

    // In real usage:
    // Object message = new UserCreated("user-1", "alice@example.com");
    // byte[] json = serializer.serialize(message);
    // Object restored = serializer.deserialize(json, "io.example.UserCreated");
  }

  /**
   * Example: Using Protobuf for efficient binary serialization.
   *
   * <p>Best for:
   * <ul>
   *   <li>Production systems with bandwidth constraints
   *   <li>Mobile/IoT applications
   *   <li>gRPC-based services
   * </ul>
   */
  public static void protobufExample() {
    // Create serializer
    ProtobufMessageSerializer serializer = new ProtobufMessageSerializer();

    // Register protobuf-generated message classes
    // serializer.registerType("io.example.pb.UserCreated", UserCreated.class);
    // serializer.registerType("io.example.pb.OrderPlaced", OrderPlaced.class);

    // Check format name
    String format = serializer.formatName();
    System.out.println("Using format: " + format);

    // In real usage:
    // Object message = UserCreated.newBuilder()
    //     .setUserId("user-1")
    //     .setEmail("alice@example.com")
    //     .build();
    // byte[] protobuf = serializer.serialize(message);
    // Object restored = serializer.deserialize(protobuf, "io.example.pb.UserCreated");
  }

  /**
   * Example: Error handling with SerializationException.
   *
   * <p>Demonstrates how to catch and handle serialization errors.
   */
  public static void errorHandlingExample() {
    JacksonMessageSerializer serializer = new JacksonMessageSerializer();

    // Type not registered - will throw SerializationException
    try {
      serializer.deserialize("{}".getBytes(), "io.example.UnknownMessage");
    } catch (SerializationException e) {
      System.out.println("Failed to deserialize: " + e.getMessage());
    }

    // Corrupted data - will throw SerializationException
    try {
      serializer.registerType("io.example.Message", Object.class);
      serializer.deserialize("corrupted bytes".getBytes(), "io.example.Message");
    } catch (SerializationException e) {
      System.out.println("Data corruption detected: " + e.getMessage());
    }

    // Null parameters - will throw NullPointerException
    try {
      serializer.serialize(null);
    } catch (NullPointerException e) {
      System.out.println("Cannot serialize null");
    }
  }

  /**
   * Example: Integration with JOTP Process.
   *
   * <p>Shows how serialization fits into the process message-passing architecture.
   */
  public static void processIntegrationExample() {
    // In real JOTP application:
    // 1. Create serializer at startup
    // JacksonMessageSerializer serializer = new JacksonMessageSerializer()
    //     .registerType("io.example.StartMsg", StartMsg.class)
    //     .registerType("io.example.StopMsg", StopMsg.class);
    //
    // 2. Use in a process handler
    // var proc = Proc.spawn((state, message) -> {
    //     byte[] serialized = serializer.serialize(message);
    //     sendToRemote(serialized);
    //     return state;
    // });
    //
    // 3. Or in distributed settings
    // GlobalProcRegistry.register("my-proc", procRef, nodeId);
    // When messages cross node boundaries, serialization is automatic
  }

  /**
   * Example: Custom serializer implementation.
   *
   * <p>Shows how to implement a custom serializer (e.g., Kryo, FST).
   */
  public static class CustomKryoSerializer implements MessageSerializer {
    // Falls back to Java serialization when the Kryo library is not present.
    // Replace the body of serialize/deserialize with Kryo calls once the
    // dependency is available: kryo.writeClassAndObject(output, message).

    @Override
    public synchronized byte[] serialize(Object message) throws SerializationException {
      java.util.Objects.requireNonNull(message, "message must not be null");
      return javaSerialize(message);
    }

    @Override
    public synchronized Object deserialize(byte[] data, String messageType)
        throws SerializationException {
      java.util.Objects.requireNonNull(data, "data must not be null");
      java.util.Objects.requireNonNull(messageType, "messageType must not be null");
      return javaDeserialize(data);
    }

    @Override
    public boolean supportsType(String messageType) {
      // Java serialization can handle any Serializable type; Kryo handles all types.
      return true;
    }

    @Override
    public String formatName() {
      return "kryo";
    }
  }

  /**
   * Example: Multiple serializers for different message types.
   *
   * <p>Shows how to combine serializers for flexibility.
   */
  public static void multiSerializerExample() {
    // Some messages use JSON (interop with REST APIs)
    JacksonMessageSerializer jacksonSerializer = new JacksonMessageSerializer();
    jacksonSerializer.registerType("io.example.HttpRequest", Object.class);
    jacksonSerializer.registerType("io.example.HttpResponse", Object.class);

    // Other messages use Protobuf (high performance)
    ProtobufMessageSerializer protobufSerializer = new ProtobufMessageSerializer();
    // protobufSerializer.registerType("io.example.pb.HighFrequencyMsg", HighFrequencyMsg.class);

    // Route based on message type at runtime
    String messageType = "io.example.HttpRequest";
    if (jacksonSerializer.supportsType(messageType)) {
      System.out.println("Using Jackson for: " + messageType);
    } else if (protobufSerializer.supportsType(messageType)) {
      System.out.println("Using Protobuf for: " + messageType);
    }
  }
}
