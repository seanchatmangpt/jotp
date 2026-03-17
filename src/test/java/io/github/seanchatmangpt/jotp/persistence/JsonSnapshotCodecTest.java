package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JsonSnapshotCodec}.
 *
 * <p>Verifies JSON serialization and deserialization of state snapshots with support for various
 * Java types.
 */
@DisplayName("JsonSnapshotCodec Tests")
class JsonSnapshotCodecTest {

    private JsonSnapshotCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JsonSnapshotCodec();
    }

    @Test
    @DisplayName("Should encode and decode simple string state")
    void encodeDecode_handlesSimpleStringState() {
        String state = "Hello, World!";

        byte[] encoded = codec.encode(state);
        String decoded = codec.decode(encoded, String.class);

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    @DisplayName("Should encode and decode numeric state")
    void encodeDecode_handlesNumericState() {
        Integer state = 42;

        byte[] encoded = codec.encode(state);
        Integer decoded = codec.decode(encoded, Integer.class);

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    @DisplayName("Should encode and decode record state")
    void encodeDecode_handlesRecordState() {
        record Person(String name, int age) {}

        Person state = new Person("Alice", 30);

        byte[] encoded = codec.encode(state);
        Person decoded = codec.decode(encoded, Person.class);

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    @DisplayName("Should encode and decode complex nested state")
    void encodeDecode_handlesNestedState() {
        record Address(String street, String city) {}
        record Person(String name, Address address) {}

        Person state = new Person("Bob", new Address("123 Main St", "Springfield"));

        byte[] encoded = codec.encode(state);
        Person decoded = codec.decode(encoded, Person.class);

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    @DisplayName("Should encode and decode collections")
    void encodeDecode_handlesCollections() {
        record Container(List<String> items, Set<Integer> numbers, Map<String, Integer> mapping) {}

        Container state =
                new Container(List.of("a", "b", "c"), Set.of(1, 2, 3), Map.of("one", 1, "two", 2));

        byte[] encoded = codec.encode(state);
        Container decoded = codec.decode(encoded, Container.class);

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    @DisplayName("Should encode and decode nullable fields")
    void encodeDecode_handlesNullableFields() {
        record NullableContainer(String value, Integer number) {}

        NullableContainer state1 = new NullableContainer(null, null);
        byte[] encoded1 = codec.encode(state1);
        NullableContainer decoded1 = codec.decode(encoded1, NullableContainer.class);

        assertThat(decoded1.value()).isNull();
        assertThat(decoded1.number()).isNull();

        NullableContainer state2 = new NullableContainer("test", 42);
        byte[] encoded2 = codec.encode(state2);
        NullableContainer decoded2 = codec.decode(encoded2, NullableContainer.class);

        assertThat(decoded2.value()).isEqualTo("test");
        assertThat(decoded2.number()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should encode and decode instant timestamps")
    void encodeDecode_handlesInstantTimestamps() {
        record TimestampedEvent(String event, Instant timestamp) {}

        TimestampedEvent state = new TimestampedEvent("test", Instant.now());

        byte[] encoded = codec.encode(state);
        TimestampedEvent decoded = codec.decode(encoded, TimestampedEvent.class);

        assertThat(decoded.event()).isEqualTo(state.event());
        assertThat(decoded.timestamp()).isEqualTo(state.timestamp());
    }

    @Test
    @DisplayName("Should produce valid UTF-8 JSON")
    void encode_producesValidUtf8Json() {
        String state = "Hello, 世界! 🌍";

        byte[] encoded = codec.encode(state);
        String jsonString = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(jsonString).contains("\"value\":\"Hello, 世界! 🌍\"");
    }

    @Test
    @DisplayName("Should handle empty string state")
    void encodeDecode_handlesEmptyString() {
        String state = "";

        byte[] encoded = codec.encode(state);
        String decoded = codec.decode(encoded, String.class);

        assertThat(decoded).isEqualTo("");
    }

    @Test
    @DisplayName("Should handle null values in records")
    void encodeDecode_handlesNullInRecords() {
        record WithNulls(String name, String value) {}

        WithNulls state = new WithNulls("test", null);

        byte[] encoded = codec.encode(state);
        WithNulls decoded = codec.decode(encoded, WithNulls.class);

        assertThat(decoded.name()).isEqualTo("test");
        assertThat(decoded.value()).isNull();
    }

    @Test
    @DisplayName("Should handle large collections")
    void encodeDecode_handlesLargeCollections() {
        record LargeList(List<Integer> items) {}

        var largeList = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 1000; i++) {
            largeList.add(i);
        }

        LargeList state = new LargeList(largeList);

        byte[] encoded = codec.encode(state);
        LargeList decoded = codec.decode(encoded, LargeList.class);

        assertThat(decoded.items()).hasSize(1000);
        assertThat(decoded.items()).containsExactlyElementsOf(largeList);
    }

    @Test
    @DisplayName("Should encode to compact JSON without whitespace")
    void encode_producesCompactJson() {
        record Person(String name, int age) {}
        Person state = new Person("Alice", 30);

        byte[] encoded = codec.encode(state);
        String jsonString = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);

        // Should not have unnecessary whitespace
        assertThat(jsonString).doesNotContain("  ");
        assertThat(jsonString).doesNotContain("\n");
    }

    @Test
    @DisplayName("Should handle special characters in strings")
    void encodeDecode_handlesSpecialCharacters() {
        String state = "Test with quotes: \"hello\", newlines: \n, tabs: \t";

        byte[] encoded = codec.encode(state);
        String decoded = codec.decode(encoded, String.class);

        assertThat(decoded).isEqualTo(state);
    }

    @Test
    @DisplayName("Should handle Unicode characters")
    void encodeDecode_handlesUnicode() {
        String state = "Unicode: 𝕌𝕟𝕚𝕔𝕠𝕕𝕖 😊 🎉";

        byte[] encoded = codec.encode(state);
        String decoded = codec.decode(encoded, String.class);

        assertThat(decoded).isEqualTo(state);
    }
}
