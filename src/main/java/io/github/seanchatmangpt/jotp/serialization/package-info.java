/**
 * Message Serialization SPI for JOTP.
 *
 * <p>This package provides a pluggable serialization framework that allows JOTP applications to
 * choose their preferred message encoding format. Instead of mandating a single serialization
 * strategy, JOTP provides a Service Provider Interface (SPI) that developers can implement.
 *
 * <h2>Core Interfaces</h2>
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.serialization.MessageSerializer} — SPI for custom
 *       serialization implementations
 *   <li>{@link io.github.seanchatmangpt.jotp.serialization.SerializationException} — Error handling
 *       for serialization failures
 * </ul>
 *
 * <h2>Built-in Implementations</h2>
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.serialization.JacksonMessageSerializer} — JSON format
 *       (human-readable, interoperable)
 *   <li>{@link io.github.seanchatmangpt.jotp.serialization.ProtobufMessageSerializer} — Protocol
 *       Buffers (efficient binary format)
 * </ul>
 *
 * <h2>Design Philosophy</h2>
 *
 * <p><strong>Network transport is pluggable.</strong> JOTP doesn't mandate gRPC, JSON, protobuf,
 * or any specific format. Instead, it defines a simple contract:
 *
 * <ol>
 *   <li>A message object (typically a record or enum)
 *   <li>Serialize it to bytes using the chosen format
 *   <li>Send those bytes to a remote node
 *   <li>Deserialize on the other side using the same format
 * </ol>
 *
 * <p>This is inspired by Erlang/OTP's design, where the Erlang external term format (ETF) is
 * built-in but applications can use other formats. Java developers have different needs:
 *
 * <ul>
 *   <li><strong>Interoperability:</strong> Want to talk to Node.js, Python, Go services? Use
 *       JSON.
 *   <li><strong>Performance:</strong> Need ultra-low latency? Use protobuf or Kryo.
 *   <li><strong>Custom Requirements:</strong> Need encryption, compression, or domain-specific
 *       encoding? Implement {@code MessageSerializer}.
 * </ul>
 *
 * <h2>Type Registry Pattern</h2>
 *
 * <p>All serializers maintain a type registry mapping message type identifiers (strings) to Java
 * classes. This allows the framework to:
 *
 * <ul>
 *   <li>Validate at startup that all message types are registered
 *   <li>Provide clear error messages when a type is unknown
 *   <li>Support polymorphic deserialization (sealed interfaces)
 * </ul>
 *
 * <pre>{@code
 * var serializer = new JacksonMessageSerializer()
 *     .registerType("com.example.UserCreated", UserCreated.class)
 *     .registerType("com.example.UserDeleted", UserDeleted.class);
 * }</pre>
 *
 * <h2>Quick Start</h2>
 *
 * <pre>{@code
 * // 1. Create serializer (Jackson is the default choice)
 * var serializer = new JacksonMessageSerializer()
 *     .registerType("io.example.MyEvent", MyEvent.class);
 *
 * // 2. Serialize a message
 * MyEvent event = new MyEvent("data");
 * byte[] bytes = serializer.serialize(event);
 *
 * // 3. Deserialize it back
 * MyEvent restored = (MyEvent) serializer.deserialize(bytes, "io.example.MyEvent");
 *
 * // 4. Use in JOTP processes
 * var proc = Proc.spawn((state, message) -> {
 *     byte[] serialized = serializer.serialize(message);
 *     sendToRemote(serialized);
 *     return state;
 * });
 * }</pre>
 *
 * <h2>Comparison: Jackson vs Protobuf</h2>
 *
 * <table border="1">
 *   <tr>
 *     <th>Aspect</th>
 *     <th>Jackson (JSON)</th>
 *     <th>Protobuf</th>
 *   </tr>
 *   <tr>
 *     <td>Format</td>
 *     <td>Text (human-readable)</td>
 *     <td>Binary (compact)</td>
 *   </tr>
 *   <tr>
 *     <td>Speed</td>
 *     <td>Moderate</td>
 *     <td>Fast (2-10x)</td>
 *   </tr>
 *   <tr>
 *     <td>Message Size</td>
 *     <td>Large (baseline)</td>
 *     <td>Small (60-80% smaller)</td>
 *   </tr>
 *   <tr>
 *     <td>Interop</td>
 *     <td>Universal (REST, GraphQL)</td>
 *     <td>Requires protobuf ecosystem</td>
 *   </tr>
 *   <tr>
 *     <td>Setup</td>
 *     <td>Easy (just register classes)</td>
 *     <td>Requires code generation from .proto files</td>
 *   </tr>
 *   <tr>
 *     <td>Best For</td>
 *     <td>Development, APIs, debugging</td>
 *     <td>Production, high performance, mobile</td>
 *   </tr>
 * </table>
 *
 * <h2>Custom Implementation Example</h2>
 *
 * <p>To implement a custom serializer (e.g., Kryo):
 *
 * <pre>{@code
 * public class KryoMessageSerializer implements MessageSerializer {
 *     private final Kryo kryo = new Kryo();
 *     private final Map<String, Class<?>> typeRegistry = new ConcurrentHashMap<>();
 *
 *     public KryoMessageSerializer registerType(String typeName, Class<?> clazz) {
 *         typeRegistry.put(typeName, clazz);
 *         return this;
 *     }
 *
 *     @Override
 *     public byte[] serialize(Object message) throws SerializationException {
 *         try (Output output = new Output(new ByteArrayOutputStream())) {
 *             kryo.writeClassAndObject(output, message);
 *             return output.toBytes();
 *         }
 *     }
 *
 *     @Override
 *     public Object deserialize(byte[] data, String messageType) throws SerializationException {
 *         try (Input input = new Input(new ByteArrayInputStream(data))) {
 *             return kryo.readClassAndObject(input);
 *         }
 *     }
 *
 *     @Override
 *     public boolean supportsType(String messageType) {
 *         return true; // Kryo can handle anything
 *     }
 *
 *     @Override
 *     public String formatName() {
 *         return "kryo";
 *     }
 * }
 * }</pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>{@link io.github.seanchatmangpt.jotp.serialization.SerializationException} is thrown when:
 *
 * <ul>
 *   <li>A message type is not registered
 *   <li>The underlying codec (Jackson, protobuf) encounters an error
 *   <li>Data is corrupted or malformed
 *   <li>A required library is missing from the classpath
 * </ul>
 *
 * <p>Following JOTP's "let it crash" philosophy, serialization errors are not caught locally.
 * Instead, the process crashes, and the supervisor decides the recovery strategy (restart, stop,
 * propagate).
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All serializers must be thread-safe. Messages may be serialized/deserialized concurrently
 * from multiple process threads.
 *
 * <ul>
 *   <li>{@code JacksonMessageSerializer} — Jackson's ObjectMapper is thread-safe
 *   <li>{@code ProtobufMessageSerializer} — Protobuf is thread-safe
 * </ul>
 *
 * @since 1.0
 * @author JOTP Contributors
 */
package io.github.seanchatmangpt.jotp.serialization;
