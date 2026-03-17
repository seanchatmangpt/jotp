package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Comprehensive tests for the Splitter pattern with DTR documentation.
 *
 * <p>The Splitter pattern (EIP) breaks a composite message into individual parts for independent
 * processing. Each part is sent as a separate message through the channel.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Basic splitting correctness (sequence numbers, correlation IDs)
 *   <li>Out-of-order arrival (sequence numbers are correct regardless of processing order)
 *   <li>Stream-based partitioning (chunking large collections)
 *   <li>Edge cases (empty lists, single item, large collections)
 *   <li>Type safety and immutability
 *   <li>Error conditions (null inputs, invalid splitters)
 * </ul>
 */
@DisplayName("Splitter Pattern (EIP)")
class SplitterTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    /** A simple test message. */
    record Message(String id, String content) {}

    @DisplayName("Basic split: one message -> multiple parts")
    @Test
    void testBasicSplit() {
                "The Splitter pattern breaks a composite message into individual parts for"
                        + " independent processing. Each part becomes a separate message that can be"
                        + " routed and processed independently.");
                """
                List<Splitter.MessagePart<String>> parts =
                    Splitter.split(msg, m -> List.of("Hello", "World"));

                assertThat(parts)
                    .hasSize(2)
                    .allSatisfy(p -> {
                        assertThat(p.payload()).isNotNull();
                        assertThat(p.metadata()).isNotNull();
                        assertThat(p.metadata().totalParts()).isEqualTo(2);
                    });
                """,
                "java");
                """
                graph LR
                    A[Composite Message] --> B[Splitter]
                    B --> C[Part 1: seq=1/2]
                    B --> D[Part 2: seq=2/2]
                """);
                "Use when you need to process elements of a collection independently, such as"
                        + " processing individual items in an order or lines in a batch file.");

        Message msg = new Message("1", "Hello World");

        List<Splitter.MessagePart<String>> parts =
                Splitter.split(msg, m -> List.of("Hello", "World"));

        assertThat(parts)
                .hasSize(2)
                .allSatisfy(
                        p -> {
                            assertThat(p.payload()).isNotNull();
                            assertThat(p.metadata()).isNotNull();
                            assertThat(p.metadata().totalParts()).isEqualTo(2);
                        });

        // Verify sequence numbers are 1-indexed
        assertThat(parts.get(0).metadata().sequenceNumber()).isEqualTo(1);
        assertThat(parts.get(1).metadata().sequenceNumber()).isEqualTo(2);

        // Verify all parts have the same correlation ID
        UUID corrId = parts.get(0).metadata().correlationId();
        assertThat(parts.stream().map(p -> p.metadata().correlationId())).allMatch(corrId::equals);
    }

    @DisplayName("Split result is immutable")
    @Test
    void testImmutability() {
                """
                List<Splitter.MessagePart<String>> parts =
                    Splitter.split("test", m -> List.of("a", "b", "c"));

                assertThatThrownBy(() -> parts.add(...))
                    .isInstanceOf(UnsupportedOperationException.class);
                """,
                "java");
                "Immutability ensures thread safety and prevents bugs caused by unintended"
                        + " modification of message parts.");

        List<Splitter.MessagePart<String>> parts =
                Splitter.split("test", m -> List.of("a", "b", "c"));

        // Try to modify the returned list (should fail or have no effect)
        assertThatThrownBy(
                        () -> parts.add(new Splitter.MessagePart<>("d", parts.get(0).metadata())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @DisplayName("Correlation ID can be provided explicitly")
    @Test
    void testExplicitCorrelationId() {
                "All parts of a split message share the same correlation ID, which can be provided"
                        + " explicitly for tracking.");
                """
                UUID corrId = UUID.randomUUID();
                List<Splitter.MessagePart<String>> parts =
                    Splitter.split("test", m -> List.of("a", "b"), corrId);

                assertThat(parts)
                    .allSatisfy(p -> assertThat(p.metadata().correlationId()).isEqualTo(corrId));
                """,
                "java");
                "Explicit correlation IDs are useful when you need to correlate split messages with"
                        + " an external request ID or transaction ID.");

        UUID corrId = UUID.randomUUID();
        List<Splitter.MessagePart<String>> parts =
                Splitter.split("test", m -> List.of("a", "b"), corrId);

        assertThat(parts)
                .allSatisfy(p -> assertThat(p.metadata().correlationId()).isEqualTo(corrId));
    }

    @DisplayName("Auto-generated correlation IDs are unique")
    @Test
    void testUniqueCorrelationIds() {
                "If no correlation ID is provided, the splitter generates a unique UUID for each"
                        + " split operation.");
                """
                var parts1 = Splitter.split("msg1", m -> List.of("a", "b"));
                var parts2 = Splitter.split("msg2", m -> List.of("c", "d"));

                UUID corrId1 = parts1.get(0).metadata().correlationId();
                UUID corrId2 = parts2.get(0).metadata().correlationId();

                assertThat(corrId1).isNotEqualTo(corrId2);
                """,
                "java");

        var parts1 = Splitter.split("msg1", m -> List.of("a", "b"));
        var parts2 = Splitter.split("msg2", m -> List.of("c", "d"));

        UUID corrId1 = parts1.get(0).metadata().correlationId();
        UUID corrId2 = parts2.get(0).metadata().correlationId();

        assertThat(corrId1).isNotEqualTo(corrId2);
    }

    @DisplayName("Payloads are preserved exactly")
    @Test
    void testPayloadPreservation() {
                """
                List<Item> items = List.of(new Item(1, "one"), new Item(2, "two"));

                var parts = Splitter.split("container", m -> items);

                assertThat(parts.get(0).payload()).isEqualTo(new Item(1, "one"));
                assertThat(parts.get(1).payload()).isEqualTo(new Item(2, "two"));
                """,
                "java");

        record Item(int id, String value) {}

        List<Item> items = List.of(new Item(1, "one"), new Item(2, "two"), new Item(3, "three"));

        var parts = Splitter.split("container", m -> items);

        assertThat(parts.get(0).payload()).isEqualTo(new Item(1, "one"));
        assertThat(parts.get(1).payload()).isEqualTo(new Item(2, "two"));
        assertThat(parts.get(2).payload()).isEqualTo(new Item(3, "three"));
    }

    @DisplayName("Single-part split")
    @Test
    void testSinglePartSplit() {
                """
                var parts = Splitter.split("message", m -> List.of("single part"));

                assertThat(parts).hasSize(1);
                assertThat(parts.get(0).metadata().sequenceNumber()).isEqualTo(1);
                assertThat(parts.get(0).metadata().totalParts()).isEqualTo(1);
                """,
                "java");

        var parts = Splitter.split("message", m -> List.of("single part"));

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).metadata().sequenceNumber()).isEqualTo(1);
        assertThat(parts.get(0).metadata().totalParts()).isEqualTo(1);
    }

    @DisplayName("Large split (100+ parts)")
    @Test
    void testLargeSplit() {
                """
                int partCount = 150;
                var parts = Splitter.split("message", m -> {
                    List<Integer> result = new ArrayList<>();
                    for (int i = 0; i < partCount; i++) {
                        result.add(i);
                    }
                    return result;
                });

                assertThat(parts).hasSize(partCount);
                for (int i = 0; i < partCount; i++) {
                    assertThat(parts.get(i).metadata().sequenceNumber()).isEqualTo(i + 1);
                    assertThat(parts.get(i).metadata().totalParts()).isEqualTo(partCount);
                }
                """,
                "java");
                "Consider performance and memory when splitting into very large numbers of parts."
                        + " Use partitioning for batch processing.");

        int partCount = 150;
        var parts =
                Splitter.split(
                        "message",
                        m -> {
                            List<Integer> result = new ArrayList<>();
                            for (int i = 0; i < partCount; i++) {
                                result.add(i);
                            }
                            return result;
                        });

        assertThat(parts).hasSize(partCount);
        for (int i = 0; i < partCount; i++) {
            assertThat(parts.get(i).metadata().sequenceNumber()).isEqualTo(i + 1);
            assertThat(parts.get(i).metadata().totalParts()).isEqualTo(partCount);
        }
    }

    @DisplayName("Partition: split collection into chunks")
    @Test
    void testPartition() {
                "Partitioning splits a large collection into fixed-size chunks, useful for batch"
                        + " processing.");
                """
                record Batch(List<String> items) {}

                var batch = new Batch(List.of("a", "b", "c", "d", "e"));
                var parts = Splitter.partition(batch, Batch::items, 2, items -> new Batch(items));

                assertThat(parts).hasSize(3);
                assertThat(parts.get(0).payload().items()).containsExactly("a", "b");
                assertThat(parts.get(1).payload().items()).containsExactly("c", "d");
                assertThat(parts.get(2).payload().items()).containsExactly("e");
                """,
                "java");
                """
                graph LR
                    A[Batch of 5] --> B[Partition by 2]
                    B --> C[Chunk 1: a,b]
                    B --> D[Chunk 2: c,d]
                    B --> E[Chunk 3: e]
                """);
                "Use partitioning when you need to process large collections in batches to control"
                        + " memory usage or parallelize work.");

        record Batch(List<String> items) {}

        var batch = new Batch(List.of("a", "b", "c", "d", "e"));

        var parts = Splitter.partition(batch, Batch::items, 2, items -> new Batch(items));

        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).payload().items()).containsExactly("a", "b");
        assertThat(parts.get(1).payload().items()).containsExactly("c", "d");
        assertThat(parts.get(2).payload().items()).containsExactly("e");

        // All parts have same correlation ID
        UUID corrId = parts.get(0).metadata().correlationId();
        assertThat(parts.stream().map(p -> p.metadata().correlationId())).allMatch(corrId::equals);
    }

    @DisplayName("MessagePart metadata: sequence out of bounds throws IAE")
    @Test
    void testPartMetadataValidation() {
                "Message part metadata validates that sequence numbers are 1-indexed and in range.");
                """
                UUID corrId = UUID.randomUUID();

                assertThatThrownBy(() -> new Splitter.PartMetadata(corrId, 0, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1-indexed");

                assertThatThrownBy(() -> new Splitter.PartMetadata(corrId, 6, 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1-indexed");
                """,
                "java");
                "Validation catches programming errors early, preventing invalid message parts from"
                        + " being created.");

        UUID corrId = UUID.randomUUID();

        assertThatThrownBy(() -> new Splitter.PartMetadata(corrId, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-indexed");

        assertThatThrownBy(() -> new Splitter.PartMetadata(corrId, 6, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-indexed");
    }

    @DisplayName("MessagePart: describe() provides readable description")
    @Test
    void testPartDescribe() {
                """
                var part = new Splitter.MessagePart<>(
                    "payload", new Splitter.PartMetadata(UUID.randomUUID(), 3, 5));

                String desc = part.describe();
                assertThat(desc).contains("3/5").contains("correlation");
                """,
                "java");

        var part =
                new Splitter.MessagePart<>(
                        "payload", new Splitter.PartMetadata(UUID.randomUUID(), 3, 5));

        String desc = part.describe();
        assertThat(desc).contains("3/5").contains("correlation");
    }

    // Additional edge case tests...

    @DisplayName("Splitter returns empty list throws IAE")
    @Test
    void testEmptySplitterResult() {
                """
                assertThatThrownBy(() -> Splitter.split("message", m -> List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-empty");
                """,
                "java");

        assertThatThrownBy(() -> Splitter.split("message", m -> List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @DisplayName("Null message throws NPE")
    @Test
    void testNullMessage() {
                """
                assertThatThrownBy(() -> Splitter.split(null, m -> List.of("a")))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("message");
                """,
                "java");

        assertThatThrownBy(() -> Splitter.split(null, m -> List.of("a")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("message");
    }
}
