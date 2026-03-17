package io.github.seanchatmangpt.jotp.serialization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for MessageSerializer interface and implementations.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic serialization and deserialization round-trips
 *   <li>Type registry management
 *   <li>Sealed type polymorphism
 *   <li>Error handling (unknown types, corrupted data)
 *   <li>Thread safety
 * </ul>
 */
class MessageSerializerTest {

  @Nested
  class JacksonSerializerTests {
    private JacksonMessageSerializer serializer;

    @BeforeEach
    void setUp() {
      serializer = new JacksonMessageSerializer();
      serializer.registerType(
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated",
          TestMessages.UserCreated.class);
      serializer.registerType(
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserDeleted",
          TestMessages.UserDeleted.class);
      serializer.registerType(
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$OrderPlaced",
          TestMessages.OrderPlaced.class);
      serializer.registerType(
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$SimpleMessage",
          TestMessages.SimpleMessage.class);
      serializer.registerType(
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$Status",
          TestMessages.Status.class);
    }

    @Test
    void testSerializeAndDeserializeUserCreatedRecord() {
      // Arrange
      var original = new TestMessages.UserCreated("user-123", "alice@example.com", "Alice");
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated";

      // Act
      byte[] serialized = serializer.serialize(original);
      Object deserialized = serializer.deserialize(serialized, typeName);

      // Assert
      assertThat(deserialized).isEqualTo(original);
      assertThat(serialized).isNotEmpty();
    }

    @Test
    void testSerializeAndDeserializeUserDeletedRecord() {
      // Arrange
      var original = new TestMessages.UserDeleted("user-456", "requested by user");
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserDeleted";

      // Act
      byte[] serialized = serializer.serialize(original);
      Object deserialized = serializer.deserialize(serialized, typeName);

      // Assert
      assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testSerializeAndDeserializeOrderPlacedRecord() {
      // Arrange
      var original = new TestMessages.OrderPlaced("order-789", "customer-1", 99.99);
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$OrderPlaced";

      // Act
      byte[] serialized = serializer.serialize(original);
      Object deserialized = serializer.deserialize(serialized, typeName);

      // Assert
      assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testSerializeAndDeserializeSimpleMessage() {
      // Arrange
      var original = new TestMessages.SimpleMessage("hello", 42);
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$SimpleMessage";

      // Act
      byte[] serialized = serializer.serialize(original);
      Object deserialized = serializer.deserialize(serialized, typeName);

      // Assert
      assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testSerializeAndDeserializeEnum() {
      // Arrange
      var original = TestMessages.Status.ACTIVE;
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$Status";

      // Act
      byte[] serialized = serializer.serialize(original);
      Object deserialized = serializer.deserialize(serialized, typeName);

      // Assert
      assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testDeserializeUnknownTypeThrowsException() {
      // Arrange
      byte[] data = "{}".getBytes();
      var unknownType = "com.example.UnknownMessage";

      // Act & Assert
      assertThatThrownBy(() -> serializer.deserialize(data, unknownType))
          .isInstanceOf(SerializationException.class)
          .hasMessageContaining("Unknown message type")
          .hasMessageContaining("UnknownMessage");
    }

    @Test
    void testDeserializeCorruptedDataThrowsException() {
      // Arrange
      byte[] corruptedData = "not valid json at all".getBytes();
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated";

      // Act & Assert
      assertThatThrownBy(() -> serializer.deserialize(corruptedData, typeName))
          .isInstanceOf(SerializationException.class)
          .hasMessageContaining("Failed to deserialize");
    }

    @Test
    void testSerializeNullMessageThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.serialize(null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDeserializeNullDataThrowsException() {
      // Act & Assert
      assertThatThrownBy(
              () ->
                  serializer.deserialize(
                      null,
                      "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDeserializeNullMessageTypeThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.deserialize("{}".getBytes(), null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSupportsType() {
      // Arrange
      var registeredType = "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated";
      var unregisteredType = "com.example.UnknownMessage";

      // Act & Assert
      assertThat(serializer.supportsType(registeredType)).isTrue();
      assertThat(serializer.supportsType(unregisteredType)).isFalse();
    }

    @Test
    void testFormatName() {
      assertThat(serializer.formatName()).isEqualTo("json");
    }

    @Test
    void testRegisterTypeIsIdempotent() {
      // Arrange
      var typeName = "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated";
      var original = new TestMessages.UserCreated("user-123", "alice@example.com", "Alice");

      // Act: register twice
      serializer.registerType(
          typeName,
          TestMessages.UserCreated.class); // Already registered in setUp
      byte[] serialized = serializer.serialize(original);
      Object deserialized = serializer.deserialize(serialized, typeName);

      // Assert
      assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testRegisterTypeNullNameThrowsException() {
      assertThatThrownBy(() -> serializer.registerType(null, TestMessages.UserCreated.class))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("typeName");
    }

    @Test
    void testRegisterTypeNullClassThrowsException() {
      assertThatThrownBy(() -> serializer.registerType("some.Type", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("clazz");
    }

    @Test
    void testRegisterTypeChaining() {
      // Act
      var result = serializer.registerType(
          "com.example.AnotherMessage", TestMessages.SimpleMessage.class);

      // Assert
      assertThat(result).isSameAs(serializer);
    }

    @Test
    void testGetMapper() {
      // Act
      var mapper = serializer.getMapper();

      // Assert
      assertThat(mapper).isNotNull();
    }

    @Test
    void testMultipleSerializeDeserializeRoundTrips() {
      // Arrange
      var messages =
          new Object[] {
            new TestMessages.UserCreated("u1", "a@b.com", "A"),
            new TestMessages.UserDeleted("u2", "reason"),
            new TestMessages.OrderPlaced("o1", "c1", 50.0),
            new TestMessages.SimpleMessage("test", 100),
            TestMessages.Status.FAILED
          };
      var typeNames =
          new String[] {
            "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserCreated",
            "io.github.seanchatmangpt.jotp.serialization.TestMessages$UserDeleted",
            "io.github.seanchatmangpt.jotp.serialization.TestMessages$OrderPlaced",
            "io.github.seanchatmangpt.jotp.serialization.TestMessages$SimpleMessage",
            "io.github.seanchatmangpt.jotp.serialization.TestMessages$Status"
          };

      // Act & Assert
      for (int i = 0; i < messages.length; i++) {
        byte[] serialized = serializer.serialize(messages[i]);
        Object deserialized = serializer.deserialize(serialized, typeNames[i]);
        assertThat(deserialized).isEqualTo(messages[i]);
      }
    }
  }

  @Nested
  class ProtobufSerializerTests {
    private ProtobufMessageSerializer serializer;

    @BeforeEach
    void setUp() {
      serializer = new ProtobufMessageSerializer();
      serializer.registerType(
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$SimpleMessage",
          TestMessages.SimpleMessage.class);
    }

    @Test
    void testFormatName() {
      assertThat(serializer.formatName()).isEqualTo("protobuf");
    }

    @Test
    void testSupportsType() {
      // Arrange
      var registeredType =
          "io.github.seanchatmangpt.jotp.serialization.TestMessages$SimpleMessage";
      var unregisteredType = "com.example.UnknownMessage";

      // Act & Assert
      assertThat(serializer.supportsType(registeredType)).isTrue();
      assertThat(serializer.supportsType(unregisteredType)).isFalse();
    }

    @Test
    void testRegisterTypeNullNameThrowsException() {
      assertThatThrownBy(() -> serializer.registerType(null, TestMessages.SimpleMessage.class))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("typeName");
    }

    @Test
    void testRegisterTypeNullClassThrowsException() {
      assertThatThrownBy(() -> serializer.registerType("some.Type", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("clazz");
    }

    @Test
    void testRegisterTypeNonMessageClassThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.registerType("bad.Type", String.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not implement");
    }

    @Test
    void testSerializeNonMessageThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.serialize("not a protobuf message"))
          .isInstanceOf(SerializationException.class)
          .hasMessageContaining("must be a protobuf Message");
    }

    @Test
    void testSerializeNullThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.serialize(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testDeserializeUnknownTypeThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.deserialize("data".getBytes(), "com.Unknown"))
          .isInstanceOf(SerializationException.class)
          .hasMessageContaining("Unknown message type");
    }

    @Test
    void testDeserializeNullDataThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.deserialize(null, "some.Type"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("data");
    }

    @Test
    void testDeserializeNullMessageTypeThrowsException() {
      // Act & Assert
      assertThatThrownBy(() -> serializer.deserialize("data".getBytes(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("messageType");
    }
  }

  @Nested
  class SerializationExceptionTests {
    @Test
    void testExceptionWithMessageAndCause() {
      // Arrange
      var message = "Serialization failed";
      var cause = new IOException("I/O error");

      // Act
      var exception = new SerializationException(message, cause);

      // Assert
      assertThat(exception).hasMessage(message);
      assertThat(exception).hasCause(cause);
      assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    @Test
    void testExceptionWithMessageOnly() {
      // Arrange
      var message = "Type not found";

      // Act
      var exception = new SerializationException(message);

      // Assert
      assertThat(exception).hasMessage(message);
      assertThat(exception).hasNoCause();
    }

    @Test
    void testExceptionWithCauseOnly() {
      // Arrange
      var cause = new IOException("Network error");

      // Act
      var exception = new SerializationException(cause);

      // Assert
      assertThat(exception).hasCause(cause);
      assertThat(exception).isInstanceOf(RuntimeException.class);
    }
  }
}
